# Android Auto

TempoBox works as a media source on Android Auto car screens.

## What you get

- **Browsing**: four tabs on the car screen: Playlists, Artists, Albums, Genres. Drill into any of them to a track list.
- **Tap-to-queue**: tapping a track queues its whole context (the album or playlist it was browsed under) with the tapped track playing first, the same as tapping a row in the app.
- **Resuming**: the car's play button resumes your persisted queue, repeat mode included, even if the app hasn't been opened since the phone rebooted. (This honors the "Restore queue on restart" setting.)
- Metadata, album art and transport controls on the car display come from the same media session the lockscreen uses.

## Calls

Answering a call on the head unit can make some car stereos send a "play" command mid-call. TempoBox refuses to restart music during a call and resumes normally when the call ends. See [Playback-and-Queue](Playback-and-Queue.md).

## Sideloaded builds need "Unknown sources"

Android Auto only lists apps installed from the Play Store unless you enable developer mode. For a sideloaded TempoBox APK:

1. Open the **Android Auto** app on the phone (on recent Android: Settings > Connected devices > Connection preferences > Android Auto).
2. Scroll to the **Version** row and tap it about ten times until developer mode is enabled.
3. Open the menu (3-dot) > **Developer settings**.
4. Enable **Unknown sources**.
5. Reconnect the phone to the car. TempoBox appears in the Auto launcher.

This is a one-time setup per phone.
