# TempoBox user guide

TempoBox is a local-first Android music player for people with real music libraries on their device: lossless formats, careful tags, ratings, smart playlists, and proper Bluetooth behavior. No cloud account, no ads, no telemetry. Its only optional network traffic is fetching artist images from Discogs, and only if you turn that on with your own token.

## Requirements and download

- Android 8.0 (API 26) or newer.
- Download the APK from [GitHub Releases](https://github.com/kylebarkmeier/tempobox/releases) and sideload it. There is no Play Store listing.

## What it plays

MP3, FLAC, OGG (Vorbis and Opus, including `.oga` and `.opus` files), and ALAC (`.m4a`). ALAC uses the device's own decoder, present on Android 12+ and most vendor builds; see [Troubleshooting](Troubleshooting.md) if ALAC files won't play.

## Guide pages

- [Getting-Started](Getting-Started.md): install, permissions, adding your music folders, the first scan.
- [Library](Library.md): the six browse tabs, sorting, search, fast scrolling, swipe gestures, ratings and play counts.
- [Playback-and-Queue](Playback-and-Queue.md): Now Playing, shuffle flavors, repeat, the queue, the audio output picker, phone-call behavior.
- [Playlists](Playlists.md): M3U import, creating playlists, auto (smart) playlists and their rules, export behavior.
- [Tags-and-Files](Tags-and-Files.md): in-app tag editing, removing vs deleting files, artwork and Discogs artist images.
- [Android-Auto](Android-Auto.md): browsing and playing from the car screen, and the extra setup a sideloaded build needs.
- [Widget-and-Bluetooth](Widget-and-Bluetooth.md): the home screen widget, start-on-connect, button remapping, volume triple-tap gestures.
- [Settings-Reference](Settings-Reference.md): every setting, screen by screen.
- [Troubleshooting](Troubleshooting.md): common problems and their causes.

## A few things TempoBox deliberately does not do

- It holds no accounts or credentials. Scrobbling is handed to a scrobbler app you install (the Last.fm app, Pano Scrobbler, Simple Scrobbler, and others); see [Widget-and-Bluetooth](Widget-and-Bluetooth.md) and [Settings-Reference](Settings-Reference.md).
- It never modifies your audio files unless you explicitly edit tags, and ratings and play counts live in the app's database, not in your files.
- It can't pick your Bluetooth codec (LDAC, aptX, AAC): Android negotiates that at the OS level for every app. TempoBox outputs bit-perfect PCM so the system can use the best codec your headphones support.
