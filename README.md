# TempoBox 🎵

[![CI](https://github.com/kylebarkmeier/tempobox/actions/workflows/ci.yml/badge.svg)](https://github.com/kylebarkmeier/tempobox/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/kylebarkmeier/tempobox?include_prereleases)](https://github.com/kylebarkmeier/tempobox/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

A local-first Android music player built with Kotlin, Jetpack Compose,
and Media3/ExoPlayer.

TempoBox is built for people with real music libraries on their device:
lossless formats, careful tags, ratings, smart playlists, and proper
Bluetooth behavior. No cloud account, no ads, no telemetry.

## Features

**Playback**
- MP3, FLAC, OGG (Vorbis/Opus), and ALAC playback (see [Codecs](#codecs))
- Gapless-friendly Media3/ExoPlayer engine with audio-focus handling and
  pause-on-unplug
- Lockscreen & notification controls with embedded album art, plus AVRCP
  metadata for car head units and Bluetooth displays
- Repeat off / all / one, and three shuffle flavors:
  plain random, **anti-repeat** (the default; spreads out repeats of the same
  track, album and artist as far as possible), and **rating-biased**
  (favors your 5★ tracks)
- Play queue that survives restarts (configurable), with multi-select,
  swipe-to-remove, and an animated now-playing indicator; the queue opens
  scrolled to the playing track, whatever the queue order (shuffle off
  included)
- Swipe-driven Now Playing: drag the mini player up into the full view and
  back down to the pill, and swipe up from Now Playing to reveal the queue
  over it (swipe down to send it back); the top bar has the same 3-dot menu
  as everywhere else, acting on the playing track

**Library**
- Fast scanner over your chosen folders: only new/changed files get their tags
  re-read; ratings and play counts always survive rescans
- A folder that can't be read (unmounted SD card, revoked permission) is
  skipped, never treated as empty: the scan removes nothing that run, and the
  scan status line in Settings ▸ Library reports skipped files and unreadable
  folders
- Automatic rescan on startup + live folder watching (default on, configurable)
- Browse by Album Artist, Artist, Album, Genre, Tracks, and Playlists
  (each with sub-browsing, card/list switches, and 5-way sorting in both
  directions)
- Detail views sort too: artist albums and tracks, album track lists, genre
  artists/albums/tracks, and playlists, each with options that fit the list
  (track number, album order, duration, play count). Sorting a playlist only
  changes the view; the stored order stays yours
- Fast scrolling: every library list and grid (and the queue) shows a
  draggable scrollbar at the right edge while scrolling; drag the thumb to
  jump anywhere in a long list
- Search in every list: a search icon in each view's top bar (main tabs,
  detail views, and the play queue) filters the visible list as you type.
  Matching ignores case and accents, looks at title/artist/album for tracks
  and names elsewhere, and keeps the current sort order
- Per-track 1-5★ ratings and play counts
- In-app ID3/Vorbis/MP4 tag editing, single track or bulk, including setting
  the embedded album art from an image on your device
- Remove-from-library and delete-from-device (both behind confirmations);
  a deleted track disappears from the play queue and from your playlists too,
  with the `.m3u8` files rewritten to match. Files deleted outside the app
  (file manager, PC over MTP) get the same cleanup when the folder watcher or
  the next scan notices they are gone
- Artist images from Discogs (optional, bring your own token) with an
  album-art collage fallback
- Genre and artist collages show the group's most played albums, one cover
  per album

**Playlists**
- Native M3U and M3U8 support; playlists found in your library folders are
  imported automatically
- Remove single entries from a playlist with the row menu (duplicates are
  removable one copy at a time)
- Everything the app creates or modifies is written as UTF-8 **M3U8**
- **Auto (smart) playlists** built from Title / Artist / Album Artist / Album /
  Genre / Year / Rating / Track number / Disc number conditions, with `<` / `>`
  for the numeric fields. Conditions combine with AND/OR, and condition groups
  combine with AND/OR again, so "(Genre is Rock OR Genre is Metal) AND
  Year > 1990" is buildable; they update themselves as your library changes
  and are exported as `.m3u8`

**Integrations**
- **Android Auto**: browse Playlists, Artists, Albums and Genres on the car
  screen; tapping a track queues its album or playlist, and the car's play
  button resumes your persisted queue. Sideloaded builds need "Unknown
  sources" enabled in the Android Auto app's developer settings
- Voice playback (experimental): "Hey Google, play [artist/album/playlist/
  genre/track] on TempoBox", with "shuffle ..." honored, a spoken type ("play
  the artist X", "songs by X", "the album X", "my X playlist") searched
  first, "play [song] by [artist]" for same-named titles, and "play
  everything" / "shuffle my music" for the whole library; Android Auto's
  search screen works too. Matching is exact first, then whole words, and
  whether Assistant/Gemini targets a sideloaded app can vary by device.
  Setup and troubleshooting:
  [Voice commands](https://github.com/kylebarkmeier/tempobox/wiki/Voice-Commands)
  in the wiki
- **In-app voice commands** that need no assistant at all: a mic button in
  the Library top bar, on the home-screen widget, and as a Quick Settings
  tile opens a small listening dialog. Say "play [artist/album/playlist/
  genre/track]", "put on [something]", "shuffle [something]", "shuffle
  everything", "add [artist/album/song] to the queue", "play [song] next",
  "add this to [playlist]", "add [artist] to playlist [name]", "pause",
  "resume", "next", "previous", "shuffle on/off", "repeat off/all/one", or
  "volume up/down". The toast afterwards says what really happened ("Playing
  12 tracks (artist: ...)", "Added 12 tracks to queue", "Nothing in the
  library matched ..."), and every request is kept in an on-device log for
  troubleshooting (see the wiki).
  Uses your device's own speech recognizer and asks for the Microphone
  permission on first use
- Calls stay clean: music pauses for calls (audio focus) and TempoBox refuses
  to let car stereos restart it mid-call; it resumes when the call ends
- Pick the output: an audio-output button in Now Playing routes playback to
  any connected device (Bluetooth, wired, USB) or back to automatic
- Network play (DLNA/UPnP): send your music to renderers on the Wi-Fi (smart
  speakers, AVRs, TVs) from the same output picker, with seek, queue
  advancement, and device volume; and browse DLNA media servers (a NAS,
  miniDLNA, Plex, Jellyfin) from the Network drawer entry to play their
  audio on the phone
- Scrobbling through your scrobbler app: TempoBox broadcasts played tracks in
  the standard SLS format, which the Last.fm app, Pano Scrobbler, Simple
  Scrobbler, etc. pick up. No account or API keys in TempoBox
- Home screen widget: art, artist, title, shuffle/prev/play/next/repeat
- "Set album art as wallpaper" corner action
- Bluetooth: start-on-connect, media-button remapping, and triple-tap volume
  gestures that work with the screen off

**Customization**
- Custom theme colors, dark mode, optional Material You
- Configurable swipe gestures (left/right) on every library row
- Configurable corner buttons around the Now Playing artwork
- Configurable track-info lines in Now Playing
- Add your own library shortcuts to the navigation drawer

## Requirements

- **Android Studio** / **IntelliJ IDEA** recent enough for AGP 9.4,
  or VS Code (see below)
- **JDK 17+** (the Gradle toolchain targets 17)
- **Android SDK** with platform 37 (`compileSdk 37`); minimum device API is 26
  (Android 8.0)
- First build needs network access for Gradle/Maven dependencies

## Building

```bash
git clone https://github.com/kylebarkmeier/tempobox.git
cd tempobox

# Debug APK
./gradlew assembleDebug          # → app/build/outputs/apk/debug/TempoBox-debug.apk

# Release APK (unsigned unless you configure signing)
./gradlew assembleRelease
```

If `local.properties` doesn't exist, the IDE creates it; on a plain CLI set
`sdk.dir=/path/to/Android/sdk` in it (or export `ANDROID_HOME`).

## Running in development

### Android Studio / IntelliJ IDEA
1. **File ▸ Open** the project root; let Gradle sync.
2. Pick the shared **`app`** run configuration (checked in under `.run/`) and
   press Run ▶ with a device or emulator connected. Debugging (breakpoints,
   Compose Layout Inspector) works out of the box.

### VS Code
1. Install the recommended extensions (VS Code will prompt; see
   `.vscode/extensions.json`).
2. Use the built-in tasks (**Terminal ▸ Run Task…**): *Assemble debug APK*,
   *Install & launch on device*, *Run all unit tests*.
3. For debugging, use the *Launch TempoBox (Android)* configuration in
   `.vscode/launch.json` (requires the `adelphes.android-dev-ext` extension
   and a connected device).

### Command line
```bash
./gradlew installDebug
# Debug builds install side-by-side with the release app as com.tempobox.debug
adb shell am start -n com.tempobox.debug/com.tempobox.MainActivity
```

### First run on a device
1. Grant the media/notification permissions the app requests.
2. Go to **Settings ▸ Library** and add your music folder(s).
3. A scan starts automatically (or tap *Rescan library*).
4. For **tag editing and file deletion**, grant *All files access* from
   Settings ▸ Library (Android requires this for modifying files in arbitrary
   folders on API 30+).

Anything that can't work without a permission you haven't granted is greyed
out with the reason instead of failing afterwards: the *Edit ID3 tags* and
*Delete permanently* menu entries without All files access, Bluetooth
start-on-connect without the Nearby devices permission (Android 12+), and
rescanning without music access. Tapping a greyed settings row requests the
permission or opens the right system screen, and granting it un-greys the
entries when you return. In-app voice commands ask for the Microphone
permission the first time you tap a mic button; if Android stops showing the
dialog after repeated denials, the voice dialog offers the system-settings
page instead.

## Running tests

```bash
# JVM unit tests for every module (fast; includes Robolectric tests)
./gradlew testDebugUnitTest test

# One module
./gradlew :core:playback:testDebugUnitTest

# Instrumented UI tests (device/emulator required)
./gradlew :app:connectedDebugAndroidTest
```

Shared IDE run configurations exist for both suites (*All unit tests*,
*Instrumented tests*).

The unit suite covers the smart-playlist rule engine, M3U/M3U8 codec, shuffle
algorithms (including statistical anti-repeat/rating-bias properties), the SLS
scrobble broadcast, the library scanner's diffing, Room DAOs, DataStore
settings, and the shared UI action layer. The instrumented suite drives the
real app: navigation drawer, tabs, settings flows, and the double-confirmation
reset.

## Project structure

```
app/               Compose UI, navigation, widget, DI wiring
core/model         Pure Kotlin domain models (rules, sorting; no Android)
core/common        Small shared utilities
core/database      Room: tracks, playlists, aggregates
core/settings      Typed DataStore settings repository
core/tags          jaudiotagger read/write (the only tag-IO module)
core/playlist      M3U/M3U8 codec + smart playlist engine (pure Kotlin)
core/library       Scanner, folder watcher, repositories, file ops
core/playback      Media3 service, queue, shuffle engines, Bluetooth glue
core/scrobble      SLS broadcasts to the user's scrobbler app
core/artwork       Embedded-art Coil fetcher + Discogs artist images
build-logic/       Gradle convention plugins shared by all modules
docs/              Architecture notes, user guide, release guide
```

See `docs/ARCHITECTURE.md` for the module graph and key design decisions, and
`CLAUDE.md` for the condensed contributor guiderails.

## Documentation

[docs/README.md](docs/README.md) is the index for all project documentation:

- **[User guide](https://github.com/kylebarkmeier/tempobox/wiki)**: features and how to use the app,
  written for end users.
- **[Architecture](docs/ARCHITECTURE.md)**: the one-page decision record.
- **[Releasing](docs/RELEASING.md)**: cutting and signing releases.

## CI/CD

GitHub Actions runs the pipeline (see `.github/workflows/`):

- **CI** (`ci.yml`) runs on every push/PR: all unit tests, Android Lint, a
  debug APK build (uploaded as an artifact), and the instrumented Compose
  tests on an API 34 emulator. The emulator job prints a per-test summary
  (counts, each failure with its message) to the log and the job summary,
  since Gradle itself reports nothing per test for connected tests; the
  emulator's own ERROR lines about swiftshader color buffers and adb polling
  during boot are host noise, not test output.
- **Release** (`release.yml`) runs on a `v*` tag push (or manually): tests,
  builds, optionally signs, and publishes a GitHub Release with the APK and
  auto-generated notes. See [docs/RELEASING.md](docs/RELEASING.md).
- **Dependabot** files weekly grouped dependency-update PRs for Gradle and
  Actions.

Contributions: see [CONTRIBUTING.md](CONTRIBUTING.md).

## Codecs

MP3, FLAC, and OGG (Vorbis/Opus) decode with ExoPlayer's bundled software
decoders on every supported device. ALAC (`.m4a`) plays through the device's
`MediaCodec` ALAC decoder, present on Android 12+ and most vendor builds; on
the rare device without one, ALAC files are scanned into the library but can't
be decoded. (Media3's FFmpeg extension can be added for universal ALAC
support, but it must be built from source and is intentionally not a default
dependency.)

**Bluetooth quality:** Android negotiates the Bluetooth codec (LDAC, aptX/HD,
AAC, LC3/LE Audio) at the OS level; apps can't pick it. TempoBox supplies
PCM audio to the audio stack so the system can use the best codec your
headphones support, and speaks AVRCP through its MediaSession for metadata and
controls.

## Notes & limitations

- `MANAGE_EXTERNAL_STORAGE` ("All files access") is requested only for tag
  editing/deleting in arbitrary user-chosen folders. Sideloaded/F-Droid style
  distribution is unaffected; Play Store distribution of this permission
  requires a declaration.
- Scrobbling needs a scrobbler app on the device (the Last.fm app or a
  dedicated scrobbler); the Discogs artist-image integration needs your own
  (free) Discogs token, since TempoBox ships with no credentials.
- Volume triple-tap gestures can't begin when the volume is already at its
  minimum/maximum (Android emits no volume-change event to observe).
- Network play is DLNA/UPnP only: Google Cast needs Play services and AirPlay
  has no open Android sender, so neither fits a sideloaded app. Renderer
  support varies by device (format support follows the renderer's decoders;
  gapless between tracks is not attempted), tracks played from a media server
  aren't library rows (no ratings, play counts, scrobbles, or queue
  persistence), and phone and devices must be on the same Wi-Fi network.
  On Android 17+ the Network screen and output picker ask for the "Local
  network" permission the first time; if you granted it from system settings
  while TempoBox was running, fully close and reopen the app.

## License

MIT; see [LICENSE](LICENSE).
