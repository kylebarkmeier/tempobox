# Deep dive 9: Tags & editing

> Prerequisites: [Android primer](../android-primer.md) §4 (All files access);
> [deep dive 2](02-library-scanning-and-database.md) (file vs library-managed
> columns, `upsertKeepingUserData`).

Tag editing is the one place TempoBox *writes* the user's files, so this
subsystem is built around three safety properties:

1. **Single writer**: exactly one class touches audio file metadata.
2. **Write only what the user changed**: a dirty-field write set, which is
   what makes bulk edits safe.
3. **Files first, then the database, synced from what actually landed on
   disk.**

## 1. The single door: `core:tags`

[`TagIO.kt`](../../core/tags/src/main/kotlin/com/tempobox/tags/TagIO.kt)
defines two deliberately small interfaces:

```kotlin
interface TagReader {
    fun readTrack(file: File): Track?            // null = unparseable, skip it
    fun readEmbeddedArtwork(file: File): ByteArray?
}
interface TagWriter {
    fun writeTags(file: File, data: TagData): Track?   // re-read Track on success
    fun writeArtwork(file: File, imageBytes: ByteArray, mimeType: String): Boolean
}
```

Both are implemented by one `@Singleton`,
[`JAudioTaggerIO`](../../core/tags/src/main/kotlin/com/tempobox/tags/JAudioTaggerIO.kt),
bound by [`TagsModule`](../../core/tags/src/main/kotlin/com/tempobox/tags/di/TagsModule.kt).
Keeping the interfaces tiny is what lets `core:library`'s tests run against an
in-memory fake ([`FakeTagIO`](../../core/library/src/test/kotlin/com/tempobox/library/Fakes.kt))
that derives tags from file names, with no audio fixtures.

The "single door" rule (CLAUDE.md rule 6) isn't stylistic: because every tag
write funnels through `TagWriter` and every caller of it is
`LibraryRepository.editTags` (§4), the file→DB re-sync happens in exactly one
place and can't be forgotten by a new code path.

### jaudiotagger on Android

jaudiotagger is a mature JVM library covering ID3v1/v2 (MP3), Vorbis comments
(FLAC/OGG), and MP4 atoms (M4A) behind one `FieldKey` API, the reason one
`writeTags` body handles every format. Using it on Android has sharp edges the
class handles once, centrally:

- **`AndroidArtwork` only**: jaudiotagger's default artwork classes depend on
  `java.awt`, which doesn't exist on Android; touching them crashes. (The
  constraint is also why `minSdk` is 26: the library needs `java.nio`.)
- Its `java.util.logging` output on malformed frames is silenced once per
  process in `init`.
- Everything is wrapped in `runCatching`: corrupt files, unsupported fields
  and IO errors turn into null/false returns and a log line, never an
  exception that aborts a scan or a bulk edit.

Reading notes (`readTrack`): title falls back to the file name; albumArtist
falls back artist → `"Unknown Artist"` *at scan time* so the DB's albumArtist
column is never empty (the aggregate queries group on it;
[deep dive 2 §2](02-library-scanning-and-database.md)); year is extracted by a
tolerant `parseYear` (first 4-digit run, handles `1994`, `1994-06-21`,
`21/06/1994`); `.m4a` is disambiguated into ALAC vs plain AAC by the codec
string in the header (`detectFormat`), since the extension alone can't tell a
lossless file from a lossy one.

Writing notes (`writeTags`): **only non-null fields of `TagData` are
applied** (the file-level half of the dirty-field contract, §3), then
`audio.commit()` saves, and the method returns a **fresh `readTrack` of the
same file** so callers persist exactly what jaudiotagger actually wrote (it
may normalize values). `writeArtwork` replaces the embedded image as an
ID3 "front cover" (APIC type 3) `AndroidArtwork`. (As of this writing
`writeArtwork` has no production caller; the capability exists behind the
single-writer interface, but no UI invokes it yet.)

## 2. The editor UI: prefill from current values

The "Edit ID3 tag(s)" modal
([`TagEditorDialog`](../../app/src/main/kotlin/com/tempobox/ui/components/TagEditorDialog.kt))
is opened by the shared action layer for *any* `LibraryItem`: one track or a
whole album/genre/selection
(`ActionDialogHost` resolves the item to tracks first). What the user sees
(behavior from PR #20):

- **Single track**: every field prefilled with the current tag; track#/disc#
  shown.
- **Bulk**: a field is prefilled when **all selected tracks agree** on its
  value; where they differ it's empty with a "Multiple values" placeholder and
  a "left unchanged" hint. Title and per-track numbers are hidden (bulk-writing
  one title over an album is never what anyone means).
- Numeric fields validate as integers before Save enables.

## 3. The pure core: `TagEditForm` and the write set

The logic behind the dialog lives in `core:model`, so it's plain-JVM testable
and shared, not buried in a composable:

[`TagEditForm.from(tracks)`](../../core/model/src/main/kotlin/com/tempobox/model/TagEditForm.kt)
seeds each field as a `TagField(value, isMixed)`: `value` is the shared text
(trivially the track's value for a single track), `isMixed` flags
disagreement.

`TagEditForm.deriveEdits(finalTexts…)` then computes the **write set** as a
[`TagData`](../../core/model/src/main/kotlin/com/tempobox/model/TagData.kt),
whose contract is the heart of the feature:

> Null fields mean "leave unchanged": the tag writer only touches non-null
> fields… A non-null empty string erases that tag.

A field becomes non-null **only when its trimmed text differs from its seed**:

- untouched fields → null → untouched in every file;
- a *mixed* field left empty → equals its seed ("") → null → each track
  **keeps its own differing value**, the case that makes bulk editing safe;
- clearing a prefilled text field → "" ≠ seed → writes "" → erases that tag
  everywhere (deliberate);
- numeric fields can't be erased by blanking (a blanked number parses to
  null = unchanged), matching what the writer can express.

So "edit the genre of 300 tracks across 40 albums" writes exactly one field
to 300 files and cannot clobber their 40 different album tags. `TagData` also
has `isEmpty`, which short-circuits a no-op save before any file IO.

## 4. The write path: files first, then the DB

[`LibraryRepository.editTags(trackIds, data)`](../../core/library/src/main/kotlin/com/tempobox/library/LibraryRepository.kt):

```
for each track:
    tagWriter.writeTags(file, data)          // file write + re-read
      ├─ null  → collect into `failed`
      └─ Track → carry over id/rating/playCount/dateAddedMs, map toEntity()
trackDao.upsertKeepingUserData(updated)      // DB syncs to what's on disk
return failed                                 // UI reports per-file failures
```

Why this order and shape:

- **The file is the source of truth** ([deep dive 2](02-library-scanning-and-database.md)),
  so the DB must reflect *the re-read result*, not the requested edit. If
  jaudiotagger normalized or partially applied something, the library shows
  the file's reality, and the next rescan finds mtime/size matching the DB
  and does nothing, rather than "correcting" the DB back.
- **DB-before-file would lie** on any write failure (row updated, file not),
  and the lie would persist until a forced rescan.
- **Failures are data, not exceptions**: a bulk edit keeps going past
  unwritable files and returns them; the action layer's toast tells the user
  how many failed and points at the fix,
  *"grant All files access in Settings ▸ Library"*, because the overwhelmingly
  common cause on API 30+ is the missing `MANAGE_EXTERNAL_STORAGE` grant
  ([primer §4](../android-primer.md)).
- The carried-over fields mean a tag edit can never reset ratings/play counts;
  it is the same `upsertKeepingUserData` guarantee the scanner relies on.

After the DAO write, reactivity does the rest: the tracks table emits, so
every library view, aggregate, and smart playlist reflects the edit with no
explicit refresh. (If the edited file is also in the play queue, the queue's
copy of the metadata, stashed in `MediaItem` extras, updates on the next
enqueue of that track; the timeline deliberately isn't rewritten for a tag
edit.)

Ratings, for contrast, are **not** tag writes at all: `setRating` is a pure
DB column update. The design treats ratings as library state (like play
counts), so rating your files never modifies them and needs no storage
permission.

## 5. Tests

- [`TagEditFormTest`](../../core/model/src/test/kotlin/com/tempobox/model/TagEditFormTest.kt)
  pins the seed/dirty-set semantics (shared vs mixed seeding, null vs ""
  derivation) as plain JUnit.
- [`YearParsingTest`](../../core/tags/src/test/kotlin/com/tempobox/tags/YearParsingTest.kt)
  covers the tolerant year parser.
- [`LibraryRepositoryTest`](../../core/library/src/test/kotlin/com/tempobox/library/LibraryRepositoryTest.kt)
  exercises `editTags` end-to-end against Robolectric Room +
  [`FakeTagIO`](../../core/library/src/test/kotlin/com/tempobox/library/Fakes.kt),
  including the failure path (`failingPaths` simulates permission denial) and
  the user-data preservation.
- jaudiotagger's actual byte-level writing is a **documented deliberate gap**
  (it would need real audio fixtures; covered manually). The seam is the
  `TagReader`/`TagWriter` interface, and everything above the seam is tested.
