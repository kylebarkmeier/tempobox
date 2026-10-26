# TempoBox agent guiderails

Concise context for AI agents (and humans) working on this repo. Design rationale: `docs/ARCHITECTURE.md`.

## What this is
Android music player. Kotlin 2.4 / Jetpack Compose / Material 3 / Media3 (ExoPlayer) / Room / Hilt / DataStore.
Package root: `com.tempobox`. minSdk 26, targetSdk 35, compileSdk 37. Gradle 9.8.0 + AGP 9.4.1 (built-in Kotlin, no kotlin-android plugin), convention plugins in `build-logic/`.

## Module map (dependencies point downward only)
- `core:model`: pure Kotlin data types. No Android deps. Everything depends on it.
- `core:common`: small utilities (time/format, dispatcher qualifiers, sort).
- `core:database`: Room entities, DAOs, `TempoBoxDatabase`.
- `core:settings`: DataStore-backed `SettingsRepository` (all user prefs, typed).
- `core:tags`: jaudiotagger wrapper. Read/write ID3v2 / Vorbis / MP4 tags and artwork.
- `core:playlist`: M3U/M3U8 parse and write (writes are always M3U8), smart-playlist rule engine.
- `core:library`: scanner, file watcher, `LibraryRepository`, file delete/remove ops.
- `core:playback`: `PlaybackService` (a `MediaLibraryService`: session + Android Auto browse tree), `PlayerConnection`, queue reordering, shuffle engines, Bluetooth glue, voice (`VoiceCommand` grammar, `VoiceSearch` matching, `VoiceActions` execution with honest outcomes, `VoiceLog` on-device request log), `ActionMessages` (queue/playlist toast wording shared by tap and voice).
- `core:remoteplay`: network play (DLNA/UPnP). SSDP discovery, AVTransport/RenderingControl SOAP control of renderers, ContentDirectory browsing of media servers, token-scoped HTTP stream server.
- `core:scrobble`: SLS broadcasts handing played tracks to the user's scrobbler app.
- `core:artwork`: Discogs artist images and album-art collage fallback.
- `app`: all Compose UI, navigation drawer/tabs, widget (Glance), the voice entry points (`VoiceCommandActivity` dialog, Quick Settings tile, widget mic), DI wiring.

## Hard rules
1. Dependency versions ONLY in `gradle/libs.versions.toml`.
2. UI never touches DAOs or files directly. Always repositories.
3. All persisted user-visible playlists are written as `.m3u8` (UTF-8), whatever they were imported as.
4. Business logic lives in `core:*` with unit tests; composables stay thin.
5. New settings: add key + typed accessor in `core:settings`, expose via `SettingsRepository`, never read DataStore elsewhere.
6. Tag writes go through the `core:tags` `TagWriter` interface only (single writer; `LibraryRepository.editTags` re-syncs the DB after the file write).
7. Tests accompany every behavioral change. JVM tests preferred; Robolectric when a Context is needed.
8. Commit style: imperative subject, no co-author or attribution trailers on ANY commit, branch commits included: a squash merge copies branch-commit trailers into main's history, where a co-author counts as a repo contributor. Before merging a PR, check its branch commits for trailers.

## Logging and error reporting
- Every `runCatching` either logs its failure (W/E with the throwable) or carries a comment saying why not.
- `CancellationException` is always rethrown, never logged as a failure. Around suspending code use `resultOf` from `core:common` instead of `runCatching`.
- A failure the user triggered reaches the snackbar with the real reason, not a guessed one ("not allowed to set the wallpaper" is not "no album art").
- Check the result of every file operation (`delete()`, `mkdirs()`, writes) and report or log honestly; never show "Deleted" for a file that is still there.
- Never cache an error as data. Only genuine answers (found / genuinely absent) go in caches; auth, rate-limit, server and transport failures are retried.
- Media3 session-callback futures go through `loggedFuture` (core:playback): the framework swallows a future's exception, so log before rethrowing.
- The scanner never deletes rows it could not verify: an unreadable location or failed walk skips the removal phase and is counted in `ScanState.Done`.
- Log once per cause where a site runs per item or per emission (seen-sets like `JAudioTaggerIO.loggedArtworkFailures`); spam hides signal.
- Grey out UI whose feature cannot work without an ungranted permission (disabled, one-line reason, tap action to grant) via `rememberAppPermissions`; keep the failure snackbar as backstop.
- Every voice request (in-app dialog, assistant intent, session search, Auto search) records one `VoiceLog` line: source, heard text with alternatives, parsed command or `none`, real outcome. A new voice entry point or command must record there too (`VoiceActions.handle` does it for in-app commands). The file lives in `noBackupFilesDir` because it holds transcripts. Pull from a debug build: `adb exec-out run-as com.tempobox.debug cat no_backup/voice/commands.log`.

## Workflow
- Never commit to main. Branch, open a PR with a descriptive title and body (the squash title becomes the commit on main and feeds auto-generated release notes), then `gh pr merge --auto --squash`. A ruleset on main enforces squash merges and required status checks.
- Before pushing, run the unit test tasks for every module you touched. Lint and the full build are CI's job.
- Instrumented tests run on the CI emulator on every PR and every push to main. They gate merges once the job is a required status check, so a PR is not done while they fail.
- One topic per PR. Resolve conflicts by rebasing the branch; never force-push main.

## Safety
- A personal phone may be connected over adb. Never run connected tests, install builds, or touch on-device app data unless the user asked for that in the current session.
- Never commit credentials: keystores, `local.properties`, API tokens. Release signing material exists only in GitHub Actions secrets and the maintainer's offline backup.
- Audio tags and playlist files are untrusted input. Validate paths from them (no traversal outside the library folders) and keep parser failures non-fatal.
- App network traffic is exactly: Discogs artist images (HTTPS) and DLNA/UPnP network play on the local network (SSDP discovery, SOAP control, cleartext HTTP streaming). In-app voice goes through the device's speech service, not an app network call. Adding any other network call, permission, or third-party service needs explicit maintainer sign-off first; signed off so far: ACCESS_LOCAL_NETWORK (network play) and RECORD_AUDIO (in-app voice).
- Destructive library operations (file delete, tag write) stay behind an explicit user confirmation in the UI and report per-file failures.

## Accuracy
- Verify names and behavior against the code before writing docs, comments, or this file. Stale references have burned us before (`TagEditor`, `QueuePersistence`, and `MediaLibraryService` were all wrong here at one point).
- A behavioral change updates its documentation in the same PR: `docs/ARCHITECTURE.md` and the README when user-facing. When the change is user-visible, update the wiki user guide (https://github.com/kylebarkmeier/tempobox/wiki) alongside the merge.
- Report test results as they are. A change that only compiled is not "tested"; name the tasks that ran.

## Writing style (docs, comments, PR bodies)
- No em dashes. Use a comma, colon, period, or parentheses instead.
- Plain, direct English. Avoid filler and AI-flavored diction ("comprehensive", "robust", "seamless", "leverage", "delve", "it's worth noting", "serves as", rhetorical triads). Describe, don't market.
- Comments state constraints the code can't show, not narration of the next line.

## Gotchas
- jaudiotagger: use `AndroidArtwork` (never awt-based artwork classes); requires `minSdk 26` (java.nio).
- Robolectric tests pin `sdk=35` in each module's `src/test/resources/robolectric.properties`: Robolectric 4.17 needs at least JDK 21 to emulate SDK 36+, and CI runs tests on JDK 17.
- Media3: the service side is a `MediaLibraryService` (serves Android Auto's browse tree too); UI code always goes through `PlayerConnection` (never a raw ExoPlayer reference in composables).
- Never loop per-item timeline ops (`moveMediaItem`/`removeMediaItem`) over a queue: each timeline change re-broadcasts the whole queue to Bluetooth AVRCP, which can OOM the BT stack and ANR the app. Batch through `QueueReorder`.
- CodeQL cannot parse Kotlin 2.4 yet, so the CodeQL workflow scans only the `actions` language. Re-enable `java-kotlin` when the extractor catches up.
- ALAC plays via device MediaCodec; see README "Codecs".
- DLNA network play is cleartext HTTP: the app's network security config permits cleartext globally (Discogs stays HTTPS). Android 17+ also gates LAN sockets behind the runtime permission ACCESS_LOCAL_NETWORK; discovery sends fail with EPERM without it, and a grant made while the process runs takes effect only after an app restart.
- The queue is persisted across restarts in DataStore by `PlaybackStateStore` in `core:playback`.
