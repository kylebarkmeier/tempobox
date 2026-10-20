# Library

The Library screen is the app's home. It has six tabs:

| Tab | Shows |
|---|---|
| **Album Artists** | Artists grouped by the album-artist tag (one entry per band, however many guest artists appear on tracks) |
| **Artists** | Artists grouped by the per-track artist tag |
| **Albums** | All albums |
| **Genres** | All genres |
| **Tracks** | Every track, flat |
| **Playlists** | Static and auto playlists (see [Playlists](Playlists.md)) |

Tap an artist, album, genre or playlist to open its detail view; detail views sub-browse further (an artist's albums and tracks, a genre's artists, albums and tracks).

## Card and list views

The artist, album and genre tabs can show either artwork cards or a plain list; a toggle button in the top bar switches. Artists and albums default to cards, Genres to a list. Artist cards show a photo from Discogs when that option is on (see [Tags-and-Files](Tags-and-Files.md)), otherwise a collage of the artist's most played album covers. Genre cards use the same collage idea: the genre's most played albums, one cover each.

## Genre chips on the artist tabs

Both artist tabs show a row of genre filter chips at the top. Tap a genre to narrow the list to artists in that genre; tap it again (or "All genres") to clear.

## Sorting

Every main tab sorts five ways, ascending or descending: **alphabetical**, **recently added**, **last modified**, **rating**, and **year**. Detail views have their own sort menus with options that fit the list: album track lists default to disc/track number, artist and genre track lists to album order, artist albums to release year, playlists to their stored order (plus duration and play count where it makes sense). Sorting a playlist only changes the view; the stored order is untouched.

## Search

Every list has free-form search: tap the search icon in the top bar and the title swaps for a text field. Typing filters the visible list live. Matching ignores case and accents, and looks at title, artist and album for tracks, and names for everything else. The current sort order is kept inside the results. The query is never saved; leaving the view clears it.

## Fast scrolling

Every library list and grid (and the play queue) shows a draggable scrollbar at the right edge while scrolling. Drag the thumb to jump anywhere in a long list. It hides itself a couple of seconds after you stop scrolling.

## Row actions

Each row has a 3-dot menu with the standard actions: play, shuffle, play next, add to queue, add to playlist, create auto playlist, go to artist, go to album, rate, edit tags, remove from library, delete permanently. The same actions are available everywhere an item appears, so a genre, an album and a single track all offer the same menu.

## Swipe gestures

Swiping a row left or right triggers a configurable action. Defaults: swipe left = **Add to queue**, swipe right = **Add to playlist**. You can bind either direction to: Nothing, Add to queue, Add to playlist, Create auto playlist, Shuffle, Remove from library, Edit ID3 tags, or Delete permanently (Settings > UI). Swipes require a deliberate drag, not a scroll flick, and the destructive ones always show a confirmation dialog first.

## Ratings and play counts

- Rate any track 1 to 5 stars from its menu (or a Now Playing corner button). Tapping the current star again clears the rating.
- Ratings and play counts are stored in TempoBox's database, never written into your files, so rating needs no storage permission and survives rescans.
- A play is counted (and scrobbled) when a track at least 30 seconds long has played half its duration or 4 minutes, whichever is less: the Last.fm standard.

## Remove vs delete

- **Remove from library** takes the item out of TempoBox's database. Files stay on disk (a later scan of the same folder would re-import them).
- **Delete permanently** deletes the files from the device. This needs All files access and is behind a confirmation dialog.

See [Tags-and-Files](Tags-and-Files.md) for details.
