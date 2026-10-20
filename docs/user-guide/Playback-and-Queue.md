# Playback and the queue

## Now Playing

While a track is loaded, a mini player pill sits at the bottom of the screen. Tap it or drag it up to expand the full Now Playing view; drag down to collapse it back to the pill. The transition follows your finger.

From the expanded view, swipe up again to reveal the **queue** over it; swipe down (or drag the queue list down past its top) to send it back. One continuous gesture can close the queue and keep pulling the sheet down to the pill. The toolbar also has a queue button. The back button closes the queue first, then collapses the sheet, then navigates.

The full view shows:

- Album artwork. Swipe it horizontally to skip tracks; pinch to zoom.
- **Corner buttons**: each of the four corners around the artwork holds a configurable action. Defaults: top left = Go to artist, top right = Go to album, bottom left = Rate track, bottom right = Add to playlist. Other options: Edit ID3 tags, Set art as wallpaper, or Nothing (Settings > Now Playing).
- **Track info lines** under the title: a configurable set of Artist, Album, Year, Genre, Format / sample rate, Bitrate, Rating, Play count (Settings > Now Playing). Defaults: artist, album, year.
- Seek bar and transport controls: previous, play/pause, next, shuffle, repeat.
- An **Audio output** button (speaker icon) in the toolbar.

## Shuffle

TempoBox has three shuffle flavors:

- **Plain**: uniform random order.
- **Anti-repeat** (the default): spreads repeats of the same track, album and artist as far apart as possible, so a 3-album queue doesn't play the same band twice in a row.
- **Rating-biased**: favors tracks with more stars, 5-star strongest.

The shuffle button toggles between off and your default mode, which is set in Settings > Shuffle (anti-repeat on/off, rating bias on/off). Long-press the shuffle button for a picker with all modes. Turning shuffle off restores the pre-shuffle order.

## Repeat

The repeat button cycles off, repeat all, repeat one.

## The queue

The queue is reachable from the navigation drawer, or by swiping up from expanded Now Playing.

- **Tap** a row to jump playback to it. The playing row carries an animated indicator.
- **Swipe** a row (a deliberate half-width drag) to remove it.
- **Play next** (from any 3-dot menu in the library or the queue) inserts after the current track; there is no drag-to-reorder.
- **Clear** empties the queue (with a confirmation by default; Settings > Queue).
- **Multi-select** via the toggle button in the top bar or a long-press on a row: then remove the selected entries, save them to a playlist, edit their tags, or apply the other standard actions in bulk.
- The queue has the same search and fast scrollbar as library lists.
- The same track can sit in the queue more than once ("Allow duplicates", on by default; each copy is removable on its own).

### Persistence

The queue, current position and repeat mode survive app restarts and reboots ("Restore queue on restart", on by default). After a restart the queue comes back paused at the right spot; TempoBox never starts playing on its own after a reboot. Shuffle mode is not re-marked on restore: the restored order is whatever shuffle produced, which is what you heard.

## Audio output picker

The speaker icon in Now Playing opens an "Audio output" dialog listing the connected outputs (Bluetooth, wired, USB) plus **Automatic**. Pick a device to route playback there; Automatic hands the choice back to the system. The choice lasts for the session, and a device that disconnects falls back to Automatic.

## Headphones, calls and other apps

- Playback pauses when headphones unplug (no accidental music over the loudspeaker).
- Audio focus is respected: navigation prompts duck the music, phone calls pause it, and it resumes after transient interruptions.
- **In-call guard**: car head units sometimes re-fire their "resume" command when you answer a call on them, which would blast music into the call. TempoBox refuses autoplay during any call and re-pauses playback that a car stereo starts mid-call; when the call ends, normal resume works again. (During VoIP calls you can still start music manually on purpose.)
- Playback continues with the screen off and when you swipe the app away from Recents.

## Lockscreen, notification, car displays

Playback controls and metadata with embedded album art appear on the lockscreen and in the media notification, and are sent to Bluetooth devices (AVRCP) for car head units and headset displays. Tapping the notification opens the app.
