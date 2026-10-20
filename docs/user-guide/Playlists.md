# Playlists

TempoBox has two playlist kinds. Both show up in the Playlists tab and both exist on disk as playlist files.

## Static playlists

An ordered track list you manage by hand.

- **Create / add**: use "Add to playlist" on any track, album, artist, genre or selection (3-dot menu, the default right-swipe, or a Now Playing corner button). Pick an existing playlist or type a new name.
- **Order**: tracks keep the order they were added in (imported playlists keep their file order). Using the sort menu in the detail view only changes how the list is displayed, never what is saved.
- **Import**: any `.m3u` / `.m3u8` file found in your library folders is imported automatically after a scan. Entries that don't match a library track are skipped. Old playlists with legacy encodings (Latin-1 `.m3u` from desktop players) are read correctly, and Windows-style backslash paths and relative paths are handled.

## Auto (smart) playlists

A smart playlist stores a rule, not a track list, and always reflects the current library: rescans, tag edits and rating changes update it instantly. You can't hand-add tracks to one.

- **Create**: "Create auto playlist" on any artist, album, genre or track seeds a rule from that item (swiping a genre seeds "Genre is X"); you can also bind it to a swipe direction. The rule builder then lets you grow the rule.
- **Rule fields**: Album Artist, Artist, Genre (text; operators *is*, *is not*, *contains*, case-insensitive) and Year, Rating (numbers; *is*, *is not*, *<*, *>*).
- **Combinations**: conditions combine with AND/OR groups, and groups nest, so "(Genre is Rock OR Genre is Metal) AND Year > 1990 AND Rating > 3" is expressible.
- Tracks in a smart playlist are listed in album order (album artist, album, disc, track).

## Files on disk

- Everything TempoBox creates or modifies is written as UTF-8 **`.m3u8`**, whatever it was imported as. An imported `.m3u` is upgraded to `.m3u8` the first time you change it; `.m3u` playlists you never touch are left as they are.
- Playlists created in the app are written to a `Playlists/` folder inside your first library location, so other apps can see them and they survive uninstalling TempoBox.
- Smart playlists are exported as a snapshot `.m3u8` (for car stereos and other players that can't evaluate rules) and the file is refreshed automatically as the library changes.
- Paths inside a playlist file are written relative when the tracks live under the playlist's folder, so a copied `Music/` tree keeps its playlists working on a new phone.

## Deleting a playlist

- **Remove from library**: drops it from TempoBox but keeps the `.m3u8` file on disk (a future scan would re-import it).
- **Delete permanently**: removes the file too.
