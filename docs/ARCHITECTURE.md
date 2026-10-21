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
  Media3 service · queue      scanner ·    │ SLS broadcasts to    Coil fetcher ·
  shuffle · BT glue           repos · ops  │ the scrobbler app    Discogs images
        │      │                   │       │        │                  │
        │      ├────────┬──────────┤       │        │                  │
        │      │  :core:playlist   │  :core:settings┴──────────────────┤
        │      │  M3U/M3U8 codec   │  DataStore repository             │
        │      │  smart engine     │       │                           │
        │ :core:remoteplay         │  :core:tags (jaudiotagger IO) ────┘
        │ SSDP · UPnP AV ·   :core:database (Room)
        │ stream server            │
        └──────┴───────┬───────────┘
                 :core:model · :core:common   (pure Kotlin, no Android)
```

Dependencies only point downward. `:core:model` and `:core:playlist` are pure
JVM modules so the most intricate logic (rule matching, sorting, playlist
formats) tests in milliseconds without an emulator. `:core:remoteplay` (used
by `:core:playback` and the `:app` network browse screen) keeps its protocol
layer pure for the same reason: SSDP messages, SOAP envelopes, DIDL-Lite and
the HTTP range logic all test on the JVM.

## Key decisions

**Aggregates are queries, not tables.** Albums/artists/genres are `GROUP BY`
projections over the `tracks` table. There is nothing to keep in sync; a tag
edit or deletion is instantly reflected everywhere, including smart playlists.

**Rescans never lose user data.** `TrackDao.upsertKeepingUserData` refreshes
tag metadata while preserving id, rating, play count, and date-added for any
path it has seen before. The scanner diffs on (mtime, size), so unchanged
files cost zero tag reads. A library location that cannot be read (revoked
permission, unmounted SD card) is skipped and counted, never treated as empty:
when any location is unreadable or a folder listing fails, the scan skips its
removal phase entirely, so a bad mount cannot wipe rows and their ratings.
`ScanState.Done` carries the skip counts and the Settings scan line shows
them.

**Smart playlists are evaluated live.** A smart playlist stores only its rule
tree (JSON). Reads evaluate against the current library, so new matching
tracks appear automatically; the spec's "kept up to date" requirement falls
out of the design instead of needing a sync job. Their `.m3u8` exports are
refreshed (debounced) whenever the track table changes.

**The ExoPlayer timeline IS the queue.** No parallel queue store to drift out
of sync. `PlayerConnection` mutates the timeline (uid-tagged MediaItems) and
exposes StateFlows; shuffle is applied by *reordering the timeline* with
`ShuffleEngine`, never via ExoPlayer's built-in shuffle. That is what makes
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
defaults) but never silent: a decode failure logs E once, and before an update
would overwrite an undecodable group, the raw value is preserved under
`<key>.corrupt`. Both the settings store and the playback-state store register
a `ReplaceFileCorruptionHandler` (log + reset), because a corrupt DataStore
file otherwise crashes every collector forever. `AppSettings` defaults are the
product defaults, asserted by tests (e.g. auto-rescan ON, anti-repeat ON).

**Errors are reported, not swallowed.** Every `runCatching` either logs its
failure or carries a comment saying why not; blocks that suspend use
`resultOf` (core:common), which rethrows `CancellationException` instead of
logging a routine cancellation as a failure. File operations check their
results (a failed playlist-file delete reaches the snackbar, not a fake
"Deleted"), failures are never cached as data (a Discogs 401 is retried, only
genuine misses get the 30-day cache), and Media3 session-callback futures go
through `loggedFuture`, which logs before the framework swallows the
exception. Player errors log in the service and surface through
`PlayerConnection.errors` into the app-shell snackbar.

**Features that cannot work are greyed, not booby-trapped.** A small
permission snapshot (`rememberAppPermissions`, re-checked on every resume)
drives the UI: settings rows gate on the permission their feature needs
(start-on-connect on BLUETOOTH_CONNECT, rescan on the media-read grant) and
show the reason plus a tap-to-grant action; the tag-edit and
delete-permanently menu entries disable with the reason while All files access
is missing. The after-the-fact failure snackbars stay as backstop.

**Tag IO has exactly one door.** Only `core:tags` (jaudiotagger) touches audio
file metadata. `LibraryRepository.editTags` writes files first, then re-syncs
DB rows from what actually landed on disk, and reports per-file failures so
the UI can point users at the All-files-access grant. Embedded artwork goes
through the same door: `LibraryRepository.editArtwork` writes via `TagWriter`
and bumps each row's mtime so the path+mtime artwork cache key misses.

## Playback pipeline details

- `PlaybackService` (MediaLibraryService) owns the ExoPlayer. Lockscreen,
  notification, AVRCP, and the Android Auto browse tree all derive from the
  MediaLibrarySession; embedded tag art is served by a custom `BitmapLoader`
  resolving `tempobox-art:///` URIs.
- The Android Auto browse tree is `LibraryBrowseTree` over `BrowseIds`, a
  URL-encoded id scheme whose decode never throws (malformed ids from external
  controllers resolve to null, not a crash). Tapping a browse leaf expands in
  `onSetMediaItems` to its whole context (album or playlist) with the tapped
  track first, the same contract as tapping a row in the app; the car's play
  button rebuilds the persisted queue via `onPlaybackResumption`.
- Voice queries (Assistant play-from-search arrives as
  `requestMetadata.searchQuery`; Auto's search screen via `onSearch`) resolve
  in `VoiceSearch`: normalized exact-then-substring matching with bucket
  priority playlist > artist > album > genre > track title, a "shuffle" token
  setting the user's configured shuffle flavor. An unmatched query falls back
  to the persisted queue, else a whole-library shuffle, so voice always plays
  something. Every query logs one line under the `PlaybackService` tag.
- `CallGuard` (pure logic, audio-mode based, no phone permission) stops car
  stereos restarting music mid-call: start-on-connect is refused during any
  call mode, and playback that starts mid-call is paused after a recheck
  delay, but only for cellular calls (`MODE_IN_CALL`); VoIP is exempt so a
  deliberate play during a Meet/Discord call sticks.
- The Now Playing output picker routes audio with
  `ExoPlayer.setPreferredAudioDevice`, reached through a custom session
  command that `onConnect` grants only to TempoBox's own package.
- Play counting + scrobbling share one rule (`PlayedThreshold`): >30s track,
  half played or 4 minutes. `PlaybackStatsListener` reports per-item play time
  when a playback session ends.
- Scrobbling is delegated to the user's scrobbler app: `BroadcastScrobbler`
  emits SLS-format broadcasts (Now Playing + complete) that the Last.fm app,
  Pano Scrobbler, etc. pick up. TempoBox holds no scrobbling credentials and
  no offline queue; the scrobbler app owns both.
- The queue snapshot (track ids, index, position, repeat) persists to its own
  DataStore, debounced plus every 15s while playing; restore happens in
  `onCreate` with `playWhenReady = false`.
- Bluetooth glue lives in the service: ACL-connect autoplay, media-button
  remap in `onMediaButtonEvent`, and a `VOLUME_CHANGED_ACTION` receiver feeding
  the pure `VolumeTripleTapDetector` (screen-off only; restores the volume the
  sequence consumed).

## Network play (core:remoteplay)

Both directions of DLNA/UPnP, chosen over Google Cast (needs Play services on
the device, wrong fit for a sideloaded app) and AirPlay (no viable open sender
stack for Android). Everything is hand-rolled on OkHttp plus plain sockets:
the protocol surface we need (one M-SEARCH, a handful of SOAP actions, HTTP
GET with ranges) is small enough that a UPnP library (jUPnP: CDDL, OSGi-heavy)
or an embedded server (NanoHTTPD: dormant upstream) would cost more than it
saves. No new dependencies.

- **Discovery** (`SsdpDiscovery`): multicast M-SEARCH for MediaRenderer and
  MediaServer devices, descriptions fetched and parsed by `DeviceDescription`.
  It needs a `MulticastLock` (CHANGE_WIFI_MULTICAST_STATE), which drains
  battery, so discovery runs only while the output picker or the Network
  screen is visible. All of SSDP, descriptions, SOAP responses and DIDL-Lite
  are untrusted network input: parsing is DTD-disabled (`SecureXml`) and
  returns null/empty instead of throwing.
- **Platform gates**: DLNA is cleartext HTTP to arbitrary LAN addresses, so
  the app's network security config permits cleartext globally (domain
  configs cannot express IP ranges; Discogs is HTTPS regardless). Android 17
  (API 37) additionally puts LAN sockets behind the runtime permission
  ACCESS_LOCAL_NETWORK: the Network screen and the output picker's network
  section request it and stay greyed until granted, and `SsdpDiscovery`
  exposes a `blocked` flow (EPERM on send) so the UI can say the OS is
  refusing, including the grant-mid-process case that only a restart clears.
- **Playing TO a renderer**: `DefaultRemotePlayController` owns one session at
  a time: AVTransport control (`RendererClient`), volume via RenderingControl,
  a 1s poll for position/transport state, and `StreamServer`, a minimal HTTP
  server bound to the WLAN address that serves only the files registered in
  `StreamTokens` under random 128-bit token paths. Request paths never touch
  file paths, so there is nothing to traverse or list. `PlayerConnection`
  stays the single playback API: the paused local ExoPlayer timeline remains
  the queue, item transitions mirror onto the renderer, the renderer's
  end-of-track advances the queue, and `RendererSessionMachine` turns
  consecutive command/poll failures into exactly one fallback to local
  playback (snackbar via `PlayerConnection.messages`). Pausing the local
  player before handoff means remote sessions never hold or fight audio
  focus. If the phone's LAN address changes, the server rebinds on the next
  load and a stale in-flight stream surfaces as the normal session fallback.
- **Playing FROM a server**: the Network drawer screen browses ContentDirectory
  (`ContentDirectoryClient`, paged Browse) and queues audio items on the local
  player by their HTTP resource URL. Network tracks are not library rows:
  `Track.isNetworkStream` (an http(s) `filePath`) opts them out of rating,
  play counting, scrobbling, and queue persistence (the snapshot stores
  library ids, so they are skipped and the saved index shifts accordingly).

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
need real audio fixtures; covered manually), and Media3 internals (owned by
AndroidX, exercised by the instrumented smoke tests).
