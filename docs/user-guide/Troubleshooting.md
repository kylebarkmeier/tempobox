# Troubleshooting

## The scan finds nothing

- Check Settings > Library > Locations actually lists your music folder. If the system folder picker couldn't express it (some SD card setups), use "Add path manually…" with the real path, e.g. `/storage/emulated/0/Music` or `/storage/XXXX-XXXX/Music`.
- Make sure the "Music and audio" (or storage) permission was granted at first launch; re-grant it from Android's app settings if you denied it.
- Only supported extensions are picked up: `.mp3`, `.flac`, `.ogg`, `.oga`, `.opus`, `.m4a`, `.mp4`, `.alac`. WAV and other formats are ignored.

## ALAC files won't play

ALAC decodes through the device's own MediaCodec decoder, present on Android 12+ and most vendor builds. On the rare device without one, ALAC files are scanned into the library but can't be decoded. MP3, FLAC and OGG always play, on every supported device, through bundled software decoders.

## Tag edits or file deletes fail

Almost always the missing **All files access** grant: Settings > Library > "Grant All files access". Android requires this permission for modifying files in arbitrary folders on Android 11+. Failures are reported per file, and the rest of a bulk edit still applies.

Files on read-only storage or with permission quirks can still fail individually; the error names them.

## Bluetooth autoplay quirks

- "Start playback on connect" is off by default; turn it on in Settings > Bluetooth & buttons.
- When it is on, playback starts about 1.5 seconds after connecting, on purpose: starting instantly would send the first second to the phone speaker before the audio route switches.
- Autoplay never fires during a phone call, and a car stereo that sends "play" mid-call is paused again. This is deliberate; see [Playback-and-Queue](Playback-and-Queue.md).

## Volume triple-tap doesn't trigger

- Gestures only arm while the screen is off.
- A gesture can't start when the volume is already at its minimum or maximum (Android emits no volume-change event to observe there). Nudge the volume off the limit first.

## Scrobbles don't show up on Last.fm

TempoBox doesn't submit scrobbles itself; it broadcasts them for a scrobbler app. You need one installed and signed in: the official Last.fm app, Pano Scrobbler, Simple Scrobbler, or another SLS-compatible scrobbler (some need "listen for broadcasts" or SLS support switched on in their settings). Also check Settings > Scrobbling in TempoBox is on (it is by default).

Play counts inside TempoBox are independent of scrobbling and always count.

## The widget doesn't update

The widget refreshes on playback events. If it ever sticks, resizing it or re-adding it forces a redraw. Keeping very large embedded artwork is handled internally (art is downscaled), so stale widgets should not happen in normal use.

## The queue didn't come back after a restart

Check Settings > Queue > "Restore queue on restart". Restored queues come back paused on purpose; a reboot never starts playback by itself. Tracks whose files were removed from the library are silently dropped from the restored queue.

## An imported playlist is missing tracks

Playlist entries that don't resolve to a file in your library folders are skipped on import. Fix the paths in the `.m3u`/`.m3u8` (or move the files into a scanned location) and rescan.
