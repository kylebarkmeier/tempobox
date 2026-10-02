# TempoBox architecture

Companion to the concise `CLAUDE.md` guiderails. Read that first; this file
explains the *why* behind the bigger decisions.

## Module graph

```
                          ┌───────────── :app ─────────────┐
                          │  Compose UI · nav · widget · DI │
                          └──┬──────┬──────┬──────┬──────┬──┘
        ┌────────────────────┘      │      │      │      └──────────────┐
  :core:playback              :core:library│ :core:scrobble       :core:artwork
  Media3 service · queue      scanner ·    │ Last.fm client       Coil fetcher ·
  shuffle · BT glue           repos · ops  │ offline queue        Discogs images
        │      │                   │       │        │                  │
        │      └────────┬──────────┤       │        │                  │
        │         :core:playlist   │  :core:settings┴──────────────────┤
        │         M3U/M3U8 codec   │  DataStore repository             │
        │         smart engine     │       │                           │
        │                          │  :core:tags (jaudiotagger IO) ────┘
        │                    :core:database (Room)
        └──────────────┬───────────┘
                 :core:model · :core:common   (pure Kotlin, no Android)
```

Dependencies only point downward. `:core:model` and `:core:playlist` are pure
JVM modules so the most intricate logic (rule matching, sorting, playlist
formats) tests in milliseconds without an emulator.

## Key decisions

**Aggregates are queries, not tables.** Albums/artists/genres are `GROUP BY`
projections over the `tracks` table. There is nothing to keep in sync; a tag
edit or deletion is instantly reflected everywhere, including smart playlists.

**Rescans never lose user data.** `TrackDao.upsertKeepingUserData` refreshes
tag metadata while preserving id, rating, play count, and date-added for any
path it has seen before. The scanner diffs on (mtime, size), so unchanged
files cost zero tag reads.

**Smart playlists are evaluated live.** A smart playlist stores only its rule
tree (JSON). Reads evaluate against the current library, so new matching
tracks appear automatically — the spec's "kept up to date" requirement falls
out of the design instead of needing a sync job. Their `.m3u8` exports are
refreshed (debounced) whenever the track table changes.

**The ExoPlayer timeline IS the queue.** No parallel queue store to drift out
of sync. `PlayerConnection` mutates the timeline (uid-tagged MediaItems) and
exposes StateFlows; shuffle is applied by *reordering the timeline* with
`ShuffleEngine`, never via ExoPlayer's built-in shuffle — that's what makes
anti-repeat and rating-biased modes possible. Un-shuffle restores the
remembered pre-shuffle uid order. Reorders and bulk removals are batched into
O(1) timeline operations (`QueueReorder`): every timeline change makes Media3
re-broadcast the *entire* queue to the platform session and on to every legacy
controller (Bluetooth AVRCP included), so one-op-per-track mutations are
quadratic parcel traffic that can OOM the Bluetooth stack and ANR the app
inside `MediaSession.setQueue`.

**One action layer for the whole UI.** `LibraryActionsViewModel` +
`LibraryItem` implement play/shuffle/queue/playlist/tag-edit/rate/remove/
delete once. Every list row, 3-dot menu, swipe gesture, corner button, and
queue bulk operation dispatches through it, so behavior (and its tests) can't
diverge between views.

**Settings are one typed snapshot.** Preferences DataStore stores each
settings *group* as JSON under one key; decoding is defensive (corrupt value →
defaults). `AppSettings` defaults are the product defaults, asserted by tests
(e.g. auto-rescan ON, anti-repeat ON).

**Tag IO has exactly one door.** Only `core:tags` (jaudiotagger) touches audio
file metadata. `LibraryRepository.editTags` writes files first, then re-syncs
DB rows from what actually landed on disk, and reports per-file failures so
the UI can point users at the All-files-access grant.

## Playback pipeline details

- `PlaybackService` (MediaSessionService) owns the ExoPlayer. Lockscreen,
  notification, and AVRCP all derive from the MediaSession; embedded tag art
  is served by a custom `BitmapLoader` resolving `tempobox-art:///` URIs.
- Play counting + scrobbling share one rule (`PlayedThreshold`): >30s track,
  half played or 4 minutes. `PlaybackStatsListener` reports per-item play time
  when a playback session ends.
- Scrobbling is delegated to the user's scrobbler app: `BroadcastScrobbler`
  emits SLS-format broadcasts (Now Playing + complete) that the Last.fm app,
  Pano Scrobbler, etc. pick up. TempoBox holds no scrobbling credentials and
  no offline queue — the scrobbler app owns both.
- The queue snapshot (track ids, index, position, repeat) persists to its own
  DataStore, debounced plus every 15s while playing; restore happens in
  `onCreate` with `playWhenReady = false`.
- Bluetooth glue lives in the service: ACL-connect autoplay, media-button
  remap in `onMediaButtonEvent`, and a `VOLUME_CHANGED_ACTION` receiver feeding
  the pure `VolumeTripleTapDetector` (screen-off only; restores the volume the
  sequence consumed).

## Testing strategy

- Pure logic (rules, sorting, codec, shuffle, thresholds, signing) → plain
  JUnit on the JVM.
- Anything needing a Context/DB (DAOs, scanner, repositories, DataStore) →
  Robolectric with in-memory Room / temp dirs; the fake tag IO derives tags
  from file names so no real audio is needed.
- Statistical properties (anti-repeat spacing, rating bias) are asserted over
  many seeded runs with documented safety margins, not single flaky samples.
- End-to-end behavior (navigation, settings flows, confirmations) →
  Compose instrumented tests on a device with the real Hilt graph.

Known deliberate gaps: jaudiotagger's actual byte-level tag writing (would
need real audio fixtures — covered manually), and Media3 internals (owned by
AndroidX, exercised by the instrumented smoke tests).
