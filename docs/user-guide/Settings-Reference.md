# Settings reference

Settings opens from the navigation drawer. Eight sections; every setting listed below with its default.

## Library

- **Locations**: the folders TempoBox scans. "Add folder…" uses the system folder browser; "Add path manually…" takes a typed path (for SD cards or folders the picker can't express). Each location has a remove button.
- **Rescan library**: scan all locations now; the row shows progress and the last scan's added/updated/removed counts.
- **Rescan on start & watch locations** (on): automatically pick up added, changed and deleted files.
- **Grant All files access**: opens the system screen for the permission that tag editing and file deletion require. Shows "All files access: granted" once given.
- **Reset library**: removes every track, rating and play count from the database. Files are not touched. Double confirmation.

## UI

- **Swipe left** (Add to queue) / **Swipe right** (Add to playlist): the action a swipe on a library row triggers. Options: Nothing, Add to queue, Add to playlist, Create auto playlist, Shuffle, Remove from library, Edit ID3 tags, Delete permanently.
- **Navigation drawer**: the four built-ins (Library, Now Playing, Queue, Settings) are fixed; "Add library shortcut…" pins any library tab to the drawer under a label you choose. Shortcuts can be removed.
- **Prefer artist photos** (on): fetch artist images from discogs.com for the card view; falls back to an album-art collage.
- **Discogs access token**: your personal token from discogs.com/settings/developers. Without one, no artist images are fetched.
- **Clear artist image cache**: re-fetch images on next browse.

## Now Playing

- **Corner buttons around album art**: one action per corner. Options: Nothing, Go to artist, Go to album, Add to playlist, Rate track, Edit ID3 tags, Set art as wallpaper. Defaults: top left Go to artist, top right Go to album, bottom left Rate track, bottom right Add to playlist.
- **Track info shown under the title**: checkboxes for Artist, Album, Year, Genre, Format / sample rate, Bitrate, Rating, Play count. Defaults: Artist, Album, Year.

## Queue

- **Restore queue on restart** (on): reload the queue, position and repeat mode after the app restarts.
- **Confirm before clearing** (on): ask before the Clear button empties the queue.
- **Allow duplicates** (on): let the same track be added to the queue more than once.

## Bluetooth & buttons

- **Start playback on connect** (off): resume automatically when a Bluetooth device or headset connects.
- **Triple-tap volume up / Triple-tap volume down** (Nothing): screen-off gesture per direction. Options: Nothing, Next track, Previous track, Play / pause, Stop.
- **Headset button remapping**: Play/pause, Next, Previous and Stop buttons each map to Default, Play / pause, Next track, Previous track, Stop, or Do nothing.

## Shuffle

- **Anti-repeat shuffle** (on): spread out repeats of the same track, album and artist as far as possible.
- **Favor higher-rated tracks** (off): bias shuffle toward tracks with more stars (5-star strongest).

These two define your default shuffle mode; the Now Playing shuffle button toggles it, and a long-press picks a mode directly. See [Playback-and-Queue](Playback-and-Queue.md).

## Scrobbling

- **Hand scrobbles to a scrobbler app** (on): broadcast played tracks for an installed scrobbler app to pick up. See [Widget-and-Bluetooth](Widget-and-Bluetooth.md).

## Theme

- **Dark mode**: Follow system / Light / Dark.
- **Material You dynamic color** (off): use system wallpaper colors (Android 12+); overrides the custom colors below.
- **Custom colors**: Primary, Secondary and Tertiary seed colors, each picked from preset swatches (Purple, Indigo, Teal, Amber, Maroon, Green, Blue, Violet). The app derives a full light and dark palette from your three picks and re-themes live.
