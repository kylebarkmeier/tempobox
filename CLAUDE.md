# TempoBox agent guiderails

Concise context for AI agents (and humans) working on this repo. Design rationale: `docs/ARCHITECTURE.md`. Subsystem walkthroughs: `docs/deep-dive/`.

## What this is
Android music player. Kotlin 2.4 / Jetpack Compose / Material 3 / Media3 (ExoPlayer) / Room / Hilt / DataStore.
Package root: `com.tempobox`. minSdk 26, targetSdk 35, compileSdk 37. Gradle 9.5.0 + AGP 9.3.1 (built-in Kotlin, no kotlin-android plugin), convention plugins in `build-logic/`.

## Module map (dependencies point downward only)
- `core:model`: pure Kotlin data types. No Android deps. Everything depends on it.
- `core:common`: small utilities (time/format, dispatcher qualifiers, sort).
- `core:database`: Room entities, DAOs, `TempoBoxDatabase`.
- `core:settings`: DataStore-backed `SettingsRepository` (all user prefs, typed).
- `core:tags`: jaudiotagger wrapper. Read/write ID3v2 / Vorbis / MP4 tags and artwork.
- `core:playlist`: M3U/M3U8 parse and write (writes are always M3U8), smart-playlist rule engine.
- `core:library`: scanner, file watcher, `LibraryRepository`, file delete/remove ops.
- `core:playback`: `PlaybackService` (a `MediaSessionService`), `PlayerConnection`, queue reordering, shuffle engines, Bluetooth glue.
- `core:scrobble`: SLS broadcasts handing played tracks to the user's scrobbler app.
- `core:artwork`: Discogs artist images and album-art collage fallback.
- `app`: all Compose UI, navigation drawer/tabs, widget (Glance), DI wiring.

## Hard rules
1. Dependency versions ONLY in `gradle/libs.versions.toml`.
2. UI never touches DAOs or files directly. Always repositories.
3. All persisted user-visible playlists are written as `.m3u8` (UTF-8), whatever they were imported as.
4. Business logic lives in `core:*` with unit tests; composables stay thin.
5. New settings: add key + typed accessor in `core:settings`, expose via `SettingsRepository`, never read DataStore elsewhere.
6. Tag writes go through the `core:tags` `TagWriter` interface only (single writer; `LibraryRepository.editTags` re-syncs the DB after the file write).
7. Tests accompany every behavioral change. JVM tests preferred; Robolectric when a Context is needed.
8. Commit style: imperative subject, no co-author trailers.

## Workflow
- Never commit to main. Branch, open a PR with a descriptive title and body (the squash title becomes the commit on main and feeds auto-generated release notes), then `gh pr merge --auto --squash`. A ruleset on main enforces squash merges and required status checks.
- Before pushing, run the unit test tasks for every module you touched. Lint and the full build are CI's job.
- Instrumented tests run on the CI emulator: on every push to main, and on a PR when it carries the `run-emulator` label. Label any PR that adds or changes instrumented tests.
- One topic per PR. Resolve conflicts by rebasing the branch; never force-push main.

## Safety
- A personal phone may be connected over adb. Never run connected tests, install builds, or touch on-device app data unless the user asked for that in the current session.
- Never commit credentials: keystores, `local.properties`, API tokens. Release signing material exists only in GitHub Actions secrets and the maintainer's offline backup.
- Audio tags and playlist files are untrusted input. Validate paths from them (no traversal outside the library folders) and keep parser failures non-fatal.
- The app's only network traffic is Discogs artist images. Adding a network call, permission, or third-party service needs explicit maintainer sign-off first.
- Destructive library operations (file delete, tag write) stay behind an explicit user confirmation in the UI and report per-file failures.

## Accuracy
- Verify names and behavior against the code before writing docs, comments, or this file. Stale references have burned us before (`TagEditor`, `QueuePersistence`, and `MediaLibraryService` were all wrong here at one point).
- A behavioral change updates its documentation in the same PR: `docs/ARCHITECTURE.md`, the matching `docs/deep-dive/` page, and the README when user-facing.
- Report test results as they are. A change that only compiled is not "tested"; name the tasks that ran.

## Writing style (docs, comments, PR bodies)
- No em dashes. Use a comma, colon, period, or parentheses instead.
- Plain, direct English. Avoid filler and AI-flavored diction ("comprehensive", "robust", "seamless", "leverage", "delve", "it's worth noting", "serves as", rhetorical triads). Describe, don't market.
- Comments state constraints the code can't show, not narration of the next line.

## Gotchas
- jaudiotagger: use `AndroidArtwork` (never awt-based artwork classes); requires `minSdk 26` (java.nio).
- Robolectric 4.14 supports at most SDK 35 (compileSdk is 37): each module with Robolectric tests pins `sdk=35` in `src/test/resources/robolectric.properties`.
- Media3: the service side is a `MediaSessionService`; UI code always goes through `PlayerConnection` (never a raw ExoPlayer reference in composables).
- Never loop per-item timeline ops (`moveMediaItem`/`removeMediaItem`) over a queue: each timeline change re-broadcasts the whole queue to Bluetooth AVRCP, which can OOM the BT stack and ANR the app. Batch through `QueueReorder`.
- CodeQL cannot parse Kotlin 2.4 yet, so the CodeQL workflow scans only the `actions` language. Re-enable `java-kotlin` when the extractor catches up.
- ALAC plays via device MediaCodec; see README "Codecs".
- The queue is persisted across restarts in DataStore by `PlaybackStateStore` in `core:playback`.
