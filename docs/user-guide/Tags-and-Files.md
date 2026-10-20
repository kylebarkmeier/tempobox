# Tags, files and artwork

## Tag editing

TempoBox edits ID3 (MP3), Vorbis comment (FLAC/OGG) and MP4 (M4A) tags in place, in your files.

**Before you start**: tag editing (and file deletion) needs **All files access**, granted from Settings > Library > "Grant All files access". Without it, edits fail with a per-file error pointing you there.

### Single track

"Edit ID3 tags" on any track (3-dot menu, a swipe binding, or a Now Playing corner button) opens the editor with every field prefilled from the current tag, including track and disc numbers.

### Bulk

Open the same editor on an album, artist, genre, playlist or multi-selection and it edits all those tracks at once, safely:

- A field is prefilled only when all selected tracks agree on its value. Where they differ it shows a "Multiple values" placeholder.
- Fields you don't touch are left unchanged in every file, so changing the genre of 300 tracks across 40 albums writes exactly one field and can't clobber their 40 different album tags.
- Clearing a prefilled text field erases that tag everywhere (deliberate).
- Title and track/disc numbers are hidden in bulk mode (writing one title over an album is never what anyone means).

### After a save

The library updates immediately: every view, aggregate and smart playlist reflects the edit with no rescan. The database is synced from what actually landed in the file, so what you see is what's really in the tag. Failures (per file) are reported, and the rest of a bulk edit still goes through. Tag edits never reset ratings or play counts.

Note: ratings are not tag writes. They live in TempoBox's database, so rating never modifies your files and needs no storage permission.

## Remove from library vs delete from device

Both live in the 3-dot menu (and can be bound to swipes); both show a confirmation first.

- **Remove from library**: forgets the tracks (or playlist) in TempoBox's database. Files stay on disk. A later scan of the same folders brings them back.
- **Delete permanently**: deletes the files from the device. Needs All files access. Per-file failures are reported.

There is also **Reset library** (Settings > Library, double-confirmed): it empties TempoBox's entire database, including ratings and play counts, and touches no files.

## Album artwork

Album art is read from the image embedded in each file's tag. The same embedded art feeds the app, the lockscreen, the notification, the widget and Bluetooth displays. TempoBox does not currently write artwork into files, and it does not fetch album art from the internet.

A "Set art as wallpaper" action can be bound to a Now Playing corner button: it sets the current track's embedded art as the device wallpaper.

## Discogs artist images (optional, bring your own token)

Artist cards can show a real artist photo instead of an album-art collage:

1. Create a free personal access token at discogs.com/settings/developers.
2. Settings > UI > Artist images > "Discogs access token": paste it.
3. Make sure "Prefer artist photos" is on (default).

When no image is found (or the option is off), the card falls back to a collage of the artist's most played album covers. "Clear artist image cache" forces a re-fetch on the next browse. This is the app's only network feature; without a token it makes no network requests at all.
