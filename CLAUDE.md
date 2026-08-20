# TempoBox — agent guiderails

Concise context for AI agents (and humans) working on this repo. Full details: `docs/ARCHITECTURE.md`.

## What this is
Android music player. Kotlin 2.0 / Jetpack Compose / Material 3 / Media3 (ExoPlayer) / Room / Hilt / DataStore.
Package root: `com.tempobox`. minSdk 26, target/compile 35. Gradle 8.14.3 + AGP 8.10.1, convention plugins in `build-logic/`.

## Module map (dependencies point downward only)
- `core:model` — pure Kotlin data types. No Android deps. Everything depends on it.
- `core:common` — small utilities (time/format, dispatchers qualifiers, sort).
- `core:database` — Room: entities, DAOs, `TempoBoxDatabase`.
- `core:settings` — DataStore-backed `SettingsRepository` (all user prefs, typed).
- `core:tags` — jaudiotagger wrapper: read/write ID3v2 / Vorbis / MP4 tags + artwork.
- `core:playlist` — M3U/M3U8 parse+write (writes are always M3U8), smart-playlist rule engine.
- `core:library` — scanner, file watcher, `LibraryRepository`, file delete/remove ops.
- `core:playback` — `PlaybackService` (MediaLibraryService), queue manager, shuffle engines, Bluetooth glue.
- `core:scrobble` — Last.fm client + offline scrobble cache.
- `core:artwork` — Discogs artist images + album-art collage fallback.
- `app` — all Compose UI, navigation drawer/tabs, widget (Glance), DI wiring.

## Hard rules
1. Dependency versions ONLY in `gradle/libs.versions.toml`.
2. UI never touches DAOs or files directly — always repositories.
3. All persisted user-visible playlists are written as `.m3u8` (UTF-8), whatever they were imported as.
4. Business logic lives in `core:*` with unit tests; composables stay thin.
5. New settings: add key + typed accessor in `core:settings`, expose via `SettingsRepository`, never read DataStore elsewhere.
6. Tag writes go through `core:tags` `TagEditor` only (single writer; syncs DB after file write).
7. Tests accompany every behavioral change. JVM tests preferred; Robolectric when a Context is needed.
8. Commit style: imperative subject, no co-author trailers.

## Gotchas
- jaudiotagger: use `AndroidArtwork` (never awt-based artwork classes); requires `minSdk 26` (java.nio).
- Robolectric tests need `@Config(sdk = [35])` max — keep in sync with compileSdk.
- Media3: service side uses `MediaLibraryService`; UI side always goes through `PlayerConnection` (never a raw ExoPlayer reference in composables).
- ALAC plays via device MediaCodec; see README "Codecs".
- The queue is persisted across restarts in DataStore (see `QueuePersistence`).
