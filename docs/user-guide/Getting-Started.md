# Getting started

## Install

1. Download the latest APK from [GitHub Releases](https://github.com/kylebarkmeier/tempobox/releases).
2. Open the downloaded file. Android will ask you to allow installs from your browser or file manager if you haven't before.
3. Launch TempoBox.

Minimum Android version: 8.0 (API 26).

## First-run permissions

On first launch TempoBox asks for the standard runtime permissions:

- **Music and audio** (on older Android versions: storage): required to read your audio files.
- **Notifications**: for the playback notification with transport controls.

A third, separate grant matters later: **All files access**. Android requires it for *modifying* files in arbitrary folders (tag editing, deleting files) on Android 11+. TempoBox does not ask for it up front; you grant it from Settings > Library > "Grant All files access" when you first want to edit tags or delete files. Playback, scanning and ratings all work without it.

## Add your music folders

1. Open the navigation drawer (the menu icon top left) and go to **Settings > Library**.
2. Under **Locations**, either:
   - **Add folder…**: pick a folder with the system folder browser, or
   - **Add path manually…**: type a path such as `/storage/emulated/0/Music` (useful for SD cards or folders the picker can't express).
3. A scan starts automatically. The **Rescan library** row shows progress and, when done, how many tracks were added, updated and removed.

## The first scan and after

- The scanner walks your chosen folders for supported audio files (`.mp3`, `.flac`, `.ogg`, `.oga`, `.opus`, `.m4a`, `.mp4`, `.alac`) and reads their tags.
- Rescans are incremental: only new or changed files get their tags re-read. Ratings and play counts always survive rescans.
- **Rescan on start & watch locations** is on by default: TempoBox rescans when it starts and watches your folders live, so files you add, change or delete are picked up without any manual rescan.
- Any `.m3u` / `.m3u8` playlist files found in your library folders are imported automatically; see [Playlists](Playlists.md).

## Next steps

- Browse your collection: [Library](Library.md).
- Play something and meet the queue: [Playback-and-Queue](Playback-and-Queue.md).
- If something looks wrong: [Troubleshooting](Troubleshooting.md).
