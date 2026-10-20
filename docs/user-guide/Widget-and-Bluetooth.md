# Widget, Bluetooth and scrobbling

## Home screen widget

Long-press your home screen > Widgets > TempoBox. The widget shows album art, artist and title, plus five controls: shuffle, previous, play/pause, next, repeat. The shuffle and repeat buttons show their active state (a tonal pill behind the icon). Tapping anywhere else on the widget opens the app. It is resizable and scales its art and controls to the size you give it; the default is 4x2 cells.

## Bluetooth and headsets

### Metadata and controls

Track metadata and album art are sent over AVRCP, so car head units and headset displays show what's playing, and their buttons control TempoBox.

### Start playback on connect

Settings > Bluetooth & buttons > "Start playback on connect" (off by default): playback resumes automatically when a Bluetooth audio device or wired headset connects. The start is delayed about 1.5 seconds so the first notes don't come out of the phone speaker while the audio route switches. Autoplay never fires during a phone call.

### Headset button remapping

Settings > Bluetooth & buttons > "Headset button remapping": each of the four media buttons (Play/pause, Next, Previous, Stop) can be remapped to Play / pause, Next track, Previous track, Stop, Do nothing, or left at Default (normal behavior). Useful for headsets with awkward button layouts.

### Volume triple-tap gestures (screen off)

Settings > Bluetooth & buttons > "Volume button gestures": triple-press a volume key while the screen is off to trigger an action: Next track, Previous track, Play / pause, or Stop (per direction; default: nothing). The volume level the three taps changed is restored automatically.

Limitations:

- Gestures only work while the screen is off (with the screen on, volume keys are just volume keys).
- A gesture can't start when the volume is already at its minimum or maximum, because Android emits no volume-change event there to observe.

### Pause on unplug

Unplugging headphones (or a Bluetooth device dropping) pauses playback so music doesn't continue over the loudspeaker.

## Scrobbling

TempoBox doesn't talk to Last.fm or any scrobbling service itself; it holds no accounts or credentials. Instead it broadcasts played tracks in the standard SLS (Simple Last.fm Scrobbler) format that scrobbler apps listen for: the official Last.fm app, Pano Scrobbler, Simple Scrobbler, and others.

- Install and sign in to a scrobbler app of your choice; most need SLS/broadcast support enabled in their own settings.
- TempoBox broadcasts "now playing" on every track start and a scrobble when the play threshold passes (at least 30 seconds long, and half the duration or 4 minutes played, whichever is less).
- The feature is on by default and can be turned off in Settings > Scrobbling.
- No scrobbler app installed means the broadcasts simply go nowhere; nothing is queued or lost inside TempoBox.
