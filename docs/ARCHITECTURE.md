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
refreshed (debounced) whenever the track table changes. Rules match the common
tag fields (title, artist, album artist, album, genre, year, rating, track and
disc number). The tree nests arbitrarily, but the builder edits a fixed
two-level view of it (`SmartRuleGroups`): conditions combined with AND/OR
inside a group, groups combined with AND/OR at the top, which covers shapes
like "(Genre is Rock OR Genre is Metal) AND Year > 1990". Builder-shaped
trees decompose and recompose exactly; a tree nested deeper than two levels
keeps every condition but folds each deep branch to its top operator, which
can change what that branch matches. Nothing in the app stores deeper trees
today, so an edit flow for arbitrary trees would need a lossless check first.

**Permanent deletion cleans up after itself.** Track rows own playlist
membership (`playlist_entries` cascades on row delete), so deleting rows
prunes static playlists in the DB for free. `deleteFromDevice` captures the
affected playlist ids before touching anything (the file watcher reacts to
the file deletions and can race the rows away mid-loop) and rewrites those
`.m3u8` files afterwards, so disk follows the DB. It reports the rows it
actually removed, and only those are pruned from the play queue
(`PlayerConnection.removeTracksFromQueue`; ids without a row, such as the
shared id 0 of network streams, never match, and the whole sequence runs
non-cancellable so leaving the screen mid-delete cannot strand half the
cleanup). Remove-from-library stays database-only: files, playlist files, and
queue entries are untouched, exactly as its confirmation dialog promises.
Files that vanish outside the app get the same treatment: the scanner's
removal phase and the watcher's incremental scan capture the affected
playlists before dropping the rows, rewrite their `.m3u8` files, and announce
the removed ids on `LibraryRemovals`; the playback service listens and drops
the matching queue entries through the same op-budgeted removal
(`QueueRemoval`), so a file deleted in a file manager cannot sit in the queue
until playback hits FILE_NOT_FOUND. `resetLibrary` stays exempt (files exist;
rewriting every playlist file on a reset would destroy them). Queue restore
remaps the stored index past vanished rows (`QueueRestore.remapIndex`), so a
snapshot whose earlier entries disappeared resumes the right track. Track
lists, aggregates, and smart playlists follow automatically because they are
DB flows.

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
delete once. Every list row, 3-dot menu, swipe gesture, corner button, the
Now Playing header's menu for the playing track, and queue bulk operation
dispatches through it, so behavior (and its tests) can't diverge between
views. The queue panel (screen and Now Playing drawer alike) opens scrolled to
the playing row, found by uid so a search filter or a shuffle-off reorder
cannot lose it.

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
is missing. A denied runtime request adds an "Open settings" action next to
the request button (after repeated denials Android reports denial without
showing a dialog, so requesting again would strand the user). The
after-the-fact failure snackbars stay as backstop.

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
- Voice queries (the assistant's play-from-search through `MainActivity`'s
  intent, another controller's as `requestMetadata.searchQuery` on the
  session, Auto's search screen via `onSearch`, the in-app dialog) resolve
  in `VoiceSearch`, which reads a query several ways and tries every reading
  for an exact name match before any reading for a whole-word match, so a
  loose reading never beats a name that matches as spoken ("Stand By Me"
  beats "The Last Stand" by Megadeth; "2" finds "Song 2" but not "22"). The
  readings, in order: as spoken with its type prefix honored ("the artist
  X", "songs by X", "album X", "my X playlist", "the song X" search one
  bucket), the same words in every bucket, then the plain words with filler
  and type prefix put back ("The Album Leaf", "Songs from the Big Chair",
  "Some Girls" are names, not instructions); each also as "X by Y" (titles
  and albums whose artist matches Y). Bucket priority within a reading is
  playlist > artist (album artists, then track artists, so a featured singer
  is reachable) > album > genre > track title. Names compare in their
  `searchKeywords` form (letters and digits only), so "Mr Brightside",
  "ACDC" and a straight apostrophe find "Mr. Brightside", "AC/DC" and a
  curly-quoted tag; a lone article or filler word never whole-word matches
  ("my" does not play "My Favorites"). A "shuffle" token sets the user's
  configured shuffle flavor; "everything", "my music", "all my songs" and the
  like resolve to the whole library in library order, while vague requests
  ("something", "some music") resolve to it shuffled; a playlist or artist
  literally named one of those phrases still wins by its exact name. Each
  bucket loads at most once per query and only when a reading reaches it,
  off the main thread (the track bucket and the shuffled whole library use
  the unsorted one-shot `getTracks`; track artists come from a names-only
  query). The assistant can only target the app by name because
  `MainActivity` declares the `MEDIA_PLAY_FROM_SEARCH` intent filter (without
  it, "play X on TempoBox" falls back to a web search); the activity hands
  the query once per launch (not again on a rotation) to
  `VoiceActions.playFromAssistant`, which resolves it in-process exactly like
  the dialog's "play X" (`PlayerConnection.playTracks`/`playShuffled`, so the
  shuffle toggle and "shuffle off" are right) and logs it with source
  `assistant-intent`; since the assistant shows no outcome, a miss there
  still plays something: the queue that is there (`PlayerConnection.resumeQueue`),
  else the persisted one (`restoreSavedQueue`: a play request on an empty
  player makes the session run `onPlaybackResumption`, the car's play-button
  path), else the whole library shuffled. The session path
  (`PlaybackService.onSetMediaItems` with a `searchQuery`) is now only for
  other controllers, Android Auto's own voice requests among them, logged as
  `session:<package>`; its miss falls back to the persisted queue, else a
  whole-library shuffle, so the car always plays something. A queue the
  service shuffles marks every item (`MediaItems.Shuffled`: flavor and
  pre-shuffle position), and `PlayerConnection.refresh` adopts such a queue
  with shuffle on and that order as the one "shuffle off" restores; any other
  foreign queue is adopted as is, and when it replaced a queue the connection
  knew, the stale shuffle flag goes with it. Auto's search screen
  resolves once: `onGetSearchResult` serves what `onSearch` announced.
- Every voice request is recorded by `VoiceLog` (`core:playback`), an
  append-only file at `no_backup/voice/commands.log` (256 KB, one rotated
  generation `.1`, one writer coroutine draining a channel so lines land in
  record order) that outlives logcat's buffer but stays out of Android Auto
  Backup, since it holds transcripts: timestamp, source (`dialog`,
  `assistant-intent`, `session:<package>`, `auto-search`), what the
  recognizer heard plus its alternative transcriptions, the parsed command
  (or `none`; a play request is `PlaySearch(query=...)` from every source,
  the Auto search screen `Search(query=...)`), and
  the real outcome, every value quoted. Each line also goes to logcat under
  the `VoiceLog` tag, the tag to filter on for voice requests (a failure
  inside the code that handled one still logs under that code's own tag).
  The service writes the `session:<package>` and `auto-search` lines, a
  failed lookup included (`outcome="failed: ..."`); `VoiceActions` writes the
  `dialog` lines (one per utterance, after the outcome is known) and the
  `assistant-intent` lines, and `VoiceCommandActivity` the recognizer
  errors, keeping any partial transcription. Pull it from a debug build with
  `adb exec-out run-as com.tempobox.debug cat no_backup/voice/commands.log`.
  A new voice entry point must record there too.
- In-app voice commands need no assistant: `VoiceCommandActivity` (a transient
  dialog reachable from the Library top bar, the home-screen widget's mic
  button, and a Quick Settings tile) captures one utterance with the device's
  `SpeechRecognizer` and hands it to `VoiceActions.handle`, which parses it
  through `VoiceCommand`, a pure, unit-tested grammar in `core:playback`,
  runs it on the application scope, logs it, and reports the outcome through
  a callback; the dialog shows "Working…" meanwhile and then the outcome
  itself for a moment before closing (a text toast is cut to two lines on
  Android 12+, which is where the reason would go; a toast is only the
  fallback when the dialog was dismissed first). `VoiceCommand.Immediate`
  commands (pause / stop / resume / next / previous / shuffle on-off / repeat
  off-all-one / volume up-down) map onto `PlayerConnection.execute`, which
  awaits the controller and answers with the confirmation or with why
  nothing changed ("Nothing to resume: the queue is empty", "Already at the
  last track"). "play X" / "shuffle X" / "put on X", "add X to the queue" /
  "queue up X", "play X next", and "add X to playlist Y" / "add this to Y"
  (this = the playing track, refused for a network stream or a track that
  left the library) resolve X and Y through `VoiceSearch` in-process, await
  the player (`playTracks`, `playShuffled`, `playNext` and `addToQueue` all
  suspend until the controller has the change; `playNext` on an empty queue
  starts playback, so "Playing N next" holds) and return the real outcome
  in the shared `ActionMessages` wording the tap path uses too ("Added 12
  tracks to queue (artist: Lamb of God)", "Nothing in the library matched",
  "No playlist named", the auto-playlist, broken-rule and playlist-gone
  refusals, which `PlaylistRepository.addToPlaylist` distinguishes with an
  `AddToPlaylistResult`); the in-app "play X" deliberately does not use the
  session's always-play-something fallback, so a misheard name says so
  instead of playing something else. The grammar rejects sentences whose
  object is only a pronoun or filler ("queue up", "play this next", "play
  my") rather than searching for those words, and treats "put on X" as a
  play request whatever X contains ("put on Born to Run"), never a playlist
  edit. When the phone has no active network (airplane mode with every radio
  off, typically) a recognizer error that an unconnected speech service
  produces ("could not
  make out any words", the network and server codes) carries that cause in
  the dialog and in the log line, since the service then hears nothing
  whatever was said. Unknown utterances and recognizer errors are shown with the real
  reason (what was heard, or the mapped `SpeechRecognizer` error), never
  silently dropped. The dialog owns the RECORD_AUDIO request; a denial
  switches it to the Open-settings recovery path, same as the local-network
  permission notes.
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
  are untrusted network input: `SecureXml` rejects any DOCTYPE outright
  (Android's parser ignores the disallow-doctype-decl feature, so the guard
  cannot rely on the parser), disables external entities where supported, and
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
