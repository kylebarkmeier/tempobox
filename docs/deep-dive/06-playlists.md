# Deep dive 6: Playlists

> Prerequisites: [deep dive 2](02-library-scanning-and-database.md) (schema,
> `LibraryInitializer`).

TempoBox has two playlist kinds with one shared contract: **everything the app
creates or modifies exists on disk as a UTF-8 `.m3u8` file.** Static playlists
are an ordered track list; smart ("auto") playlists are a stored *rule* that
is evaluated live against the library. The subsystem spans three modules:

- `core:playlist`: pure JVM, holding the M3U codec and the rule engine (fast
  unit tests, no Android).
- `core:model`: the `SmartRule` tree and its JSON serialization.
- `core:library`:
  [`PlaylistRepository`](../../core/library/src/main/kotlin/com/tempobox/library/PlaylistRepository.kt)
  for DB + file orchestration.

## 1. The M3U8 codec

[`M3uCodec`](../../core/playlist/src/main/kotlin/com/tempobox/playlist/M3uCodec.kt)
handles the file format. M3U is a line-oriented format from the WinAmp era:
`#`-prefixed directives, one media path per line; `.m3u8` is the same thing
with UTF-8 guaranteed. The codec's posture is **liberal reader, strict
writer**:

**Reading** (`read`/`parse`) accepts the mess that exists in the wild:

- `.m3u8` decodes as UTF-8; plain `.m3u` tries *strict* UTF-8 first
  (malformed-input action REPORT) and falls back to Latin-1, the de-facto
  legacy encoding, so an old WinAmp playlist with `ü` in a path still
  resolves.
- A leading BOM is stripped; blank lines and all `#` directives are skipped
  (`#EXTINF` titles are display hints; the library re-reads real tags
  anyway, so trusting them would introduce a second metadata source).
- Backslash separators are normalized (playlists written by desktop players),
  and relative entries resolve against the playlist's own directory before
  being normalized to absolute paths.

**Writing** (`serialize`/`write`) always produces UTF-8 M3U8 with
`#EXTM3U`/`#EXTINF:<seconds>,<artist> - <title>` headers. `write` *requires*
an `.m3u8` extension (a hard `require`); callers convert legacy names first
via `ensureM3u8Path("Road Trip.m3u") → "Road Trip.m3u8"`. Paths inside the
playlist's folder are written **relative** so a `Music/` tree copied to a new
phone keeps its playlists working; everything else is absolute.

All of this is pinned by JVM tests in
[`M3uCodecTest`](../../core/playlist/src/test/kotlin/com/tempobox/playlist/M3uCodecTest.kt).

## 2. The smart-rule model

[`SmartRule`](../../core/model/src/main/kotlin/com/tempobox/model/SmartRule.kt)
is a small boolean expression tree:

```kotlin
sealed interface SmartRule {
    data class Condition(val field: RuleField, val op: RuleOp, val value: String) : SmartRule
    data class AllOf(val rules: List<SmartRule>) : SmartRule   // AND
    data class AnyOf(val rules: List<SmartRule>) : SmartRule   // OR
}
```

- `RuleField`: `ALBUM_ARTIST`, `ARTIST`, `GENRE` (string; operators `IS` /
  `IS_NOT` / `CONTAINS`, case-insensitive) and `YEAR`, `RATING` (numeric;
  `IS` / `IS_NOT` / `LESS_THAN` / `GREATER_THAN`). `RuleOp.operatorsFor(field)`
  is what the rule-builder UI uses to offer only valid operators.
- Trees nest arbitrarily, so
  `(Genre is Rock OR Genre is Metal) AND Year > 1990 AND Rating > 3` is
  representable.
- Matching is a pure extension function, `SmartRule.matches(track)`: the
  single source of truth for membership, exercised heavily in
  [`SmartRuleTest`](../../core/model/src/test/kotlin/com/tempobox/model/SmartRuleTest.kt).
  Edge semantics: string fields match against the `effective*`
  fallbacks (so "Genre is Unknown Genre" works); a `YEAR` condition on a
  track with no year is false except for `IS_NOT`; a non-numeric value in a
  numeric condition matches nothing.

**Serialization**: kotlinx-serialization polymorphic JSON with
`classDiscriminator = "kind"` (`"condition"` / `"all"` / `"any"`), stored in
the playlist row's `smartRuleJson` column. `fromJson` is defensive: it returns
null instead of throwing on malformed/legacy JSON, so a bad row degrades into
an ordinary empty playlist rather than a crash.

The evaluator,
[`SmartPlaylistEngine`](../../core/playlist/src/main/kotlin/com/tempobox/playlist/SmartPlaylistEngine.kt),
filters a track collection through a rule and sorts the result album-artist →
album → disc → track → title ("plays like a sensible library slice"; the
order is deterministic, so exports don't churn). It also renders
human-readable rule descriptions for list rows (`describe`).

## 3. Live evaluation: "kept up to date" for free

A smart playlist stores **only its rule**. There is no membership table and no
sync job. Every read evaluates against the current library:

- `PlaylistRepository.observePlaylistTracks(playlist)` for a smart playlist is
  literally `trackDao.observeAll().map { engine.evaluate(rule, it) }`: a
  live Room query piped through the engine. Rescan adds a matching track →
  the DB emits → the playlist updates. Same for tag edits, rating changes
  (`Rating > 3` playlists react to a new 4★ instantly), and deletions.
- `observePlaylists()` computes smart playlists' track counts/durations the
  same way, by `combine`-ing the playlist rows with `observeAll()`.

This is the same philosophy as aggregates-as-queries
([deep dive 2 §2](02-library-scanning-and-database.md)): don't materialize
what you can derive; a per-read single pass over the track list is cheap at
library scale, and an entire class of staleness bugs never exists. The spec
requirement "auto playlists update themselves" *falls out of the design*
rather than being a feature.

## 4. Files on disk: exports, imports, and the `.m3u` upgrade

### Exports

Every mutation of a static playlist (`createPlaylist`, `addToPlaylist`,
`replacePlaylistTracks`) rewrites its `.m3u8` file (`exportStatic`). Smart
playlists export a **snapshot** of their current evaluation (`exportSmart`);
the file is for interop (car stereos, other players), which can't evaluate
rules, so it holds materialized paths.

Smart snapshots go stale as the library changes, so
[`LibraryInitializer`](../../core/library/src/main/kotlin/com/tempobox/library/LibraryInitializer.kt)
refreshes them reactively: it watches `trackDao.observeAll()`, reduces each
emission to a cheap change signature (`size` + `sum(dateModifiedMs)`), drops
the startup emission, **samples at 5 s**, and calls `refreshSmartExports()`.
The debounce matters: a bulk tag edit or a big scan changes the track table
hundreds of times; without sampling, every smart playlist's file would be
rewritten per change.

App-created playlist files live in `<first library location>/Playlists/`
(falling back to app-private storage when no location is configured). They
sit inside the library on purpose, so they're visible to other apps and
survive TempoBox being uninstalled.

### Imports

After every full scan, `importPlaylistFiles(locations)` walks the library
folders for `.m3u`/`.m3u8` files the DB doesn't know yet, parses them, and
creates static playlists from the entries that match a library track by path
(non-matching lines are skipped, with a match-count log line). Two dedup
guards: a file already tracked by path is skipped, and so is the
*upgraded twin* of a tracked `.m3u` (see below); otherwise an upgraded
playlist would re-import from its leftover sibling.

### The legacy upgrade

The invariant is "everything the app *modifies* becomes M3U8", so imported
`.m3u` files are upgraded lazily, on first write (`writeUpgraded`): write the
`.m3u8`, delete the old `.m3u`, update the row's `filePath`. Read-only `.m3u`
playlists are left untouched; the app doesn't rewrite files it was merely
shown.

## 5. The database side

([`PlaylistEntities.kt`](../../core/database/src/main/kotlin/com/tempobox/database/entity/PlaylistEntities.kt),
[`PlaylistDao`](../../core/database/src/main/kotlin/com/tempobox/database/dao/PlaylistDao.kt))

- `playlists`: name (unique, case-insensitive lookup; `uniqueName()` in the
  repository suffixes "(2)", "(3)"… on collision), optional `filePath`,
  optional `smartRuleJson`, timestamps. A row is smart iff `smartRuleJson`
  is non-null.
- `playlist_entries`: `(playlistId, trackId, position)` for static
  playlists only. `ON DELETE CASCADE` on both foreign keys: delete a playlist
  or remove a track from the library, and SQL cleans up the entries.
- `observeAllWithStats()` joins entries + tracks for live counts/durations
  (static playlists; smart ones get theirs from evaluation, §3).
- `replaceEntries` (delete-all + reinsert with fresh positions, in one
  `@Transaction`) is the reorder/remove primitive; `appendEntries` continues
  from `MAX(position)`.

`require(playlist.smartRuleJson == null)` in `addToPlaylist` enforces the
semantic split: you can't hand-add tracks to a rule-defined playlist
(the UI never offers it; the repository backstops it).

The playlist detail screen's sort menu never touches any of this: the chosen
sort is applied to the displayed list in `PlaylistDetailViewModel`
(`SortSpec.sortTracks`), and `replaceEntries` only runs for explicit edits
(entry removal, reorder). The stored position column and the `.m3u8` file keep
the user's manual order whatever the view shows
([deep dive 8](08-ui-architecture.md)).

## 6. Deletion semantics

`deletePlaylist(id, deleteFile)` encodes the product distinction surfaced by
the two confirmation dialogs
([`LibraryActionsViewModel.confirmRemoveFromLibrary` / `confirmDelete`](../../app/src/main/kotlin/com/tempobox/ui/library/LibraryActionsViewModel.kt)):
**Remove from library** drops the row but keeps the `.m3u8` on disk (it would
be re-imported by a future scan; "remove" means "stop managing it");
**Delete permanently** removes the file too.

## 7. How a playlist gets created from the UI

All creation funnels through the shared action layer
([deep dive 8](08-ui-architecture.md)):

- *Static*: "Add to playlist" on any item → `AddToPlaylistDialog` (pick
  existing or type a new name) → `addToNewPlaylist`/`addToExistingPlaylist` →
  repository.
- *Smart*: "Create auto playlist" on any artist/album/genre/track →
  `LibraryActionsViewModel.autoRuleFor(item)` seeds a one-condition rule from
  the item's paradigm (swiping a genre row seeds `Genre IS X`; an album seeds
  its album artist; a track seeds its artist) → the
  [`SmartRuleBuilderDialog`](../../app/src/main/kotlin/com/tempobox/ui/components/SmartRuleBuilderDialog.kt)
  lets the user grow the tree (AND/OR groups, numeric operators) →
  `createSmartPlaylist(name, rule)`.

Both end with an `.m3u8` on disk, a row in the DB, and, for smart playlists,
membership that tracks the library from then on with no further writes.
