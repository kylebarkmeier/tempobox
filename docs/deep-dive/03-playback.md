# Deep dive 3: Playback end-to-end

> Prerequisites: [Android primer](../android-primer.md) §2 (binder), §3
> (foreground services), §10 (Media3/MediaSession).

This document traces one tap on a track all the way to sound, then follows
the side channels: lockscreen/notification/Bluetooth rendering, artwork,
play counting, and scrobbling.

## The cast

```mermaid
sequenceDiagram
    participant UI as Compose UI<br/>(TrackRow tap)
    participant AVM as LibraryActionsViewModel
    participant PC as PlayerConnection<br/>(singleton, UI process side)
    participant MC as MediaController<br/>(binder client)
    participant PS as PlaybackService<br/>(MediaSessionService)
    participant EXO as ExoPlayer
    participant OS as OS surfaces<br/>(notification, lockscreen,<br/>Bluetooth AVRCP)

    UI->>AVM: play(item) / playFrom(tracks, index)
    AVM->>PC: playTracks(tracks, startIndex)
    PC->>MC: setMediaItems(items) · prepare() · play()
    MC-->>PS: binder RPC (strips file URIs!)
    PS->>PS: onAddMediaItems: rebuild URI from "path" extra
    PS->>EXO: timeline = items; decode & play
    EXO-->>PS: Player events (transition, isPlaying, …)
    PS-->>OS: MediaSession publishes metadata + controls
    PS-->>PC: same events via the controller
    PC-->>UI: StateFlows (state, queue) → recomposition
```

## 1. UI → `PlayerConnection`

Composables never see a player object (CLAUDE.md rule). Any play-ish gesture
(a row tap, a "Shuffle" menu item, a widget button) lands on
[`LibraryActionsViewModel`](../../app/src/main/kotlin/com/tempobox/ui/library/LibraryActionsViewModel.kt)
(the shared action layer, [deep dive 8](08-ui-architecture.md)), which resolves
the tapped item to a `List<Track>` and calls one of
[`PlayerConnection`](../../core/playback/src/main/kotlin/com/tempobox/playback/PlayerConnection.kt)'s
commands: `playTracks`, `playShuffled`, `addToQueue`, `playNext`,
`togglePlayPause`, ….

`PlayerConnection` is a `@Singleton` that:

- builds an async `MediaController` against `PlaybackService`'s session token
  (this *starts the service* on first use; binder connection ≈ lazy daemon
  spawn);
- pins all controller calls to a `Dispatchers.Main.immediate` scope, because
  a `MediaController` must be used on the thread it connected on (a Media3
  threading contract; think "this RPC stub is not thread-safe");
- exposes two hot `StateFlow`s the whole app renders from: `state:
  NowPlayingState` (track, isPlaying, position, duration, shuffle/repeat,
  queue index/size) and `queue: List<QueueItem>`.

State flows back via a `Player.Listener` on the controller: any of the
relevant events (`EVENT_TIMELINE_CHANGED`, `EVENT_MEDIA_ITEM_TRANSITION`,
`EVENT_IS_PLAYING_CHANGED`, …) triggers `refresh(controller)`, which
re-snapshots the timeline into the flows. While playing, a 500 ms ticker also
refreshes so the progress bar moves smoothly; position is *polled*, not
pushed (Media3 doesn't stream position updates over binder; that would be
pointless chatter).

## 2. Track → `MediaItem`: the envelope format

[`MediaItems`](../../core/playback/src/main/kotlin/com/tempobox/playback/MediaItems.kt)
defines the mapping both sides share:

- **`mediaId` = queue uid**: a counter-assigned id unique per *enqueue* (not
  per track), so the same track queued twice is two individually-addressable
  queue entries. The uid is the queue's primary key everywhere
  ([deep dive 4](04-queue.md)).
- **Standard metadata fields** (title/artist/album/albumArtist/artworkUri)
  go in `MediaMetadata` proper; these are what the OS surfaces and Bluetooth
  render.
- **Everything else the app needs rides in metadata `extras`** (track DB id,
  file path, duration, rating, genre, format, year). This makes the timeline
  self-describing: the queue UI, the persistence snapshot, and the scrobbler
  can all reconstruct a full `Track` from a `MediaItem`
  (`MediaItems.toTrack`) **without a database round-trip**. That matters both
  for process-death restore and because listener callbacks shouldn't block on
  IO.
- Artwork gets a custom URI scheme: `tempobox-art:///<encoded path>` (see §5).

### The binder gotcha: URIs don't survive the crossing

When a controller sends `MediaItem`s to a session, Media3 **strips
`localConfiguration`**, including the file URI, because arbitrary
controllers (e.g. another app) shouldn't learn your file paths. So items
arrive at the service playable-in-name-only, and
`PlaybackService.sessionCallback.onAddMediaItems` rebuilds the URI from the
`"path"` extra:

```kotlin
override fun onAddMediaItems(…): ListenableFuture<MutableList<MediaItem>> {
    val resolved = mediaItems.map { item ->
        val path = item.mediaMetadata.extras?.getString("path")
        if (path.isNullOrBlank()) item
        else item.buildUpon().setUri(Uri.fromFile(File(path))).build()
    }.toMutableList()
    return Futures.immediateFuture(resolved)
}
```

Every enqueue path goes through this hook, including the re-adds that
`QueueReorder` plans ([deep dive 4](04-queue.md)), which is why reordering
doesn't silently produce unplayable items.

## 3. Inside the service: ExoPlayer setup

[`PlaybackService`](../../core/playback/src/main/kotlin/com/tempobox/playback/PlaybackService.kt)
extends Media3's `MediaSessionService`: the framework base class that owns
the foreground-service lifecycle and the media notification. `onCreate`
builds the engine:

```kotlin
player = ExoPlayer.Builder(this)
    .setAudioAttributes(AudioAttributes… USAGE_MEDIA / CONTENT_TYPE_MUSIC, /* handleAudioFocus = */ true)
    .setHandleAudioBecomingNoisy(true)   // pause when headphones unplug
    .setWakeMode(C.WAKE_MODE_LOCAL)      // keep CPU awake while playing, screen off
    .build()
```

Three OS contracts in one builder:

- **Audio focus**: Android arbitrates "who may make sound". With
  `handleAudioFocus = true`, ExoPlayer pauses when another app takes focus
  (a navigation prompt ducks you, a phone call pauses you) and resumes after
  transient losses. Opting out of this is how apps end up playing over phone
  calls.
- **Becoming noisy**: the OS broadcasts "audio is about to route to the
  loudspeaker" when headphones unplug; handling it means no accidental
  music-out-loud on the train.
- **Wake mode**: a partial wake lock held only while playing, so decode
  continues with the screen off.

Then the session:

```kotlin
session = MediaSession.Builder(this, player)
    .setCallback(sessionCallback)
    .setBitmapLoader(CacheBitmapLoader(ArtworkBitmapLoader(tagReader, scope, ioDispatcher)))
    .apply { launchAppPendingIntent()?.let(::setSessionActivity) }
    .build()
```

`onGetSession` returns it, and from that moment Media3 does the rest: builds
the media notification from session state, promotes the service to foreground
while playing, and bridges to the platform session so the lockscreen and
Bluetooth AVRCP see metadata and controls. TempoBox contains **no
notification-building code at all**.

The **session activity** (`launchAppPendingIntent`) is what makes tapping the
notification or lockscreen card open the app. Detail with a reason: the
intent is resolved via `packageManager.getLaunchIntentForPackage(packageName)`
rather than naming `MainActivity`, because `core:playback` must not depend on
`:app` (where `MainActivity` lives); the module graph points strictly
downward.

`onTaskRemoved` (user swipes the app from Recents) stops the service only if
nothing is playing; music keeps going with the UI gone, which is the whole
point of putting the player in a service.

## 4. Media button handling & Bluetooth glue

Three input paths live service-side because they must work with no UI:

**Media buttons / AVRCP commands** arrive via
`sessionCallback.onMediaButtonEvent`. Default behavior is Media3's; the
override exists only for the remap feature (Settings ▸ Bluetooth): it maps the
key code to a logical
[`MediaButton`](../../core/model/src/main/kotlin/com/tempobox/model/UiConfig.kt),
looks up the user's
[`BluetoothSettings.buttonRemap`](../../core/settings/src/main/kotlin/com/tempobox/settings/AppSettings.kt),
and returns `false` (= let Media3 handle it) when the mapping is `DEFAULT`.
So the override is a conditional interceptor, not a reimplementation.

**Start-on-connect**: a receiver for `BluetoothDevice.ACTION_ACL_CONNECTED`
and wired-headset `ACTION_HEADSET_PLUG`. Two learned-the-hard-way details in
the code: `ACTION_HEADSET_PLUG` is a *sticky* broadcast, replayed instantly on
registration, so `isInitialStickyBroadcast` is ignored (otherwise playback
would start on every service start with headphones in); and playback is
delayed 1.5 s after connect because the audio route needs a moment to switch.
Play immediately and the first second comes out of the phone speaker.

**Volume triple-tap** (screen-off gesture control): Android offers no
background volume-*key* listener, but it does broadcast the (hidden-but-stable)
`android.media.VOLUME_CHANGED_ACTION` on stream-volume changes. The receiver
feeds direction + timestamp into
[`VolumeTripleTapDetector`](../../core/playback/src/main/kotlin/com/tempobox/playback/VolumeTripleTapDetector.kt):
a pure state machine (3 same-direction changes within 900 ms windows) that's
unit-tested with injected time. Gestures only arm while the screen is off
(`PowerManager.isInteractive` resets the detector; with the screen on, volume
keys should just be volume keys), and on trigger the service restores the
volume the three taps consumed. Documented limitation: at min/max volume a
press emits no change event, so a sequence can't start at the boundary.

## 5. Artwork on the lockscreen / notification / car display

`MediaItem`s don't carry artwork bytes (they'd be parceled constantly);
they carry the `tempobox-art:///` **URI**, and the session resolves it
lazily through its `BitmapLoader`:

[`ArtworkBitmapLoader`](../../core/playback/src/main/kotlin/com/tempobox/playback/ArtworkBitmapLoader.kt)
→ decodes the URI back to a file path (`MediaItems.pathFromArtworkUri`, using
`encodedPath` to avoid double-decoding paths containing literal `%`),
→ `tagReader.readEmbeddedArtwork(file)` pulls the image bytes out of the
audio file's tag (the only tag-IO door, [deep dive 9](09-tags-and-editing.md)),
→ decodes with down-sampling capped at 1024 px (`inSampleSize` powers of two;
a lockscreen doesn't need a 3000 px scan, and large bitmaps are memory +
binder poison).

It's wrapped in Media3's `CacheBitmapLoader`, so the current track's art is
decoded once, not per surface. This one loader is what feeds the
notification, the lockscreen, and AVRCP album art on car head units.

(In-app album art uses a parallel mechanism, a Coil fetcher over the same
`TagReader`; see [deep dive 1 §1](01-startup-and-di.md). Same source of
truth, different image pipeline.)

## 6. Play counting & scrobbling

**Detection** uses Media3's `PlaybackStatsListener`, an analytics listener
that calls back **once per finished playback session of an item** (on skip,
transition, or stop) with accumulated `totalPlayTimeMs`. That is strictly
better than hand-rolling position heuristics, since it already accounts for
pauses and seeks. The callback resolves the `MediaItem` from the event's timeline
and hands off to `onPlaybackSessionEnded(item, playedMs)`.

**The rule** lives in
[`PlayedThreshold`](../../core/playback/src/main/kotlin/com/tempobox/playback/PlayedThreshold.kt),
a pure object shared by play counts and scrobbles so the two can never
disagree: count iff the track is at least 30 s long **and** played ≥ half its
duration or ≥ 4 minutes, whichever is less. This is the Last.fm scrobble
standard.

**On success**, two things happen
([`PlaybackService.onPlaybackSessionEnded`](../../core/playback/src/main/kotlin/com/tempobox/playback/PlaybackService.kt)):

1. `libraryRepository.incrementPlayCount(track.id)` (guarded by `id > 0`;
   a restored item whose file left the library has no row).
2. `scrobbler.scrobble(track, startedAtEpochSec)`. The start time is
   estimated as `now − playedMs` because `PlaybackStats` reports
   elapsed-realtime, and a scrobbler fed raw elapsed-realtime would see a
   ~1970 timestamp.

**Scrobbling is delegated, not implemented.** TempoBox used to contain a
Last.fm client; it was removed in favor of the **SLS broadcast API**: the
de-facto standard intent (`com.adam.aslfms.notify.playstatechanged`) that
scrobbler apps (the official Last.fm app, Pano Scrobbler, Simple Scrobbler, …)
listen for. [`ScrobbleBroadcast`](../../core/scrobble/src/main/kotlin/com/tempobox/scrobble/ScrobbleBroadcast.kt)
is a pure extras builder (unit-testable payload:
state code, app name/package, artist, album, track, duration-seconds);
[`BroadcastScrobbler`](../../core/scrobble/src/main/kotlin/com/tempobox/scrobble/BroadcastScrobbler.kt)
wraps it in an `Intent` and `sendBroadcast`s it: `START` on every track
transition (Now Playing), `COMPLETE` when the threshold passes. It no-ops
when Settings ▸ Scrobbling is off (default on; a broadcast nobody listens to
is free).

Why this design won: the scrobbler app owns credentials, submission, retries,
and the offline queue. TempoBox holds **zero Last.fm credentials and no
network scrobbling code**; the entire integration is ~80 lines and an
`Intent`, and it works with every scrobbling service the user's scrobbler
supports. The trade-off (needs a scrobbler app installed) is documented in
the README.

## 7. Keeping the widget honest

The home-screen widget renders from `PlayerConnection.state`, but Glance
widgets only re-render when told to
([deep dive 8](08-ui-architecture.md)). The service's `Player.Listener`
broadcasts `com.tempobox.action.WIDGET_REFRESH` (package-scoped) on track
transitions, play/pause flips, and repeat-mode changes. Shuffle mode is the
odd one out: since shuffle is implemented as a timeline *reorder*
([deep dive 5](05-shuffle.md)), it isn't a player event the service can
observe, so `PlayerConnection` itself broadcasts the refresh when its
`_shuffleMode` flow changes. The constant is defined once in
`PlaybackService.ACTION_WIDGET_REFRESH` and referenced by the receiver.

## 8. Shutdown

`onDestroy` does a final `runBlocking { saveSnapshot() }` (one DataStore
write; the queue must survive even an abrupt service stop, and
[deep dive 4](04-queue.md) covers the snapshot), unregisters the receivers,
releases session and player, and cancels the scope. Queue restore on the next
`onCreate` completes the loop.
