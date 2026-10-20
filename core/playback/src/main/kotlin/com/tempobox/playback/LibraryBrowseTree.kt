package com.tempobox.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.tempobox.library.LibraryRepository
import com.tempobox.library.PlaylistRepository
import com.tempobox.model.SortKey
import com.tempobox.model.SortSpec
import com.tempobox.model.Track
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The media-browser tree served to Android Auto (and any other
 * MediaBrowser-style controller) by [PlaybackService].
 *
 * Four root tabs (Android Auto shows at most four): Playlists, Artists
 * (album artists), Albums, Genres. Branch nodes are browsable; leaves are
 * playable tracks whose id carries the node they were browsed under, so
 * [tracksFor] can queue the whole context with the tapped track first
 * (see [BrowseIds]).
 */
@Singleton
class LibraryBrowseTree @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val playlistRepository: PlaylistRepository,
) {

    fun root(): MediaItem = browsable(BrowseIds.Node.Root, "TempoBox")

    suspend fun item(mediaId: String): MediaItem? {
        val node = BrowseIds.decode(mediaId) ?: return null
        return when (node) {
            BrowseIds.Node.Root -> root()
            BrowseIds.Node.Playlists -> browsable(node, "Playlists")
            BrowseIds.Node.Artists -> browsable(node, "Artists")
            BrowseIds.Node.Albums -> browsable(node, "Albums")
            BrowseIds.Node.Genres -> browsable(node, "Genres")
            is BrowseIds.Node.Artist -> browsable(node, node.name)
            is BrowseIds.Node.Album -> browsable(node, node.album, subtitle = node.albumArtist)
            is BrowseIds.Node.Genre -> browsable(node, node.name)
            is BrowseIds.Node.Playlist ->
                playlistRepository.getPlaylist(node.id)?.let { browsable(node, it.name) }
            is BrowseIds.Node.Track -> {
                val tracks = tracksFor(node.parent)
                val track = node.index?.let { tracks.getOrNull(it) }
                    ?.takeIf { it.id == node.trackId }
                    ?: tracks.firstOrNull { it.id == node.trackId }
                track?.let { playable(it, node.parent, node.index) }
            }
        }
    }

    /** Children of a browsable node; null when [parentId] doesn't decode. */
    suspend fun children(parentId: String): List<MediaItem>? {
        val node = BrowseIds.decode(parentId) ?: return null
        return when (node) {
            BrowseIds.Node.Root -> listOf(
                browsable(BrowseIds.Node.Playlists, "Playlists"),
                browsable(BrowseIds.Node.Artists, "Artists"),
                browsable(BrowseIds.Node.Albums, "Albums"),
                browsable(BrowseIds.Node.Genres, "Genres"),
            )
            BrowseIds.Node.Playlists ->
                // Names only: the stats-bearing observePlaylists() would load the
                // whole library and evaluate every smart rule per listing.
                playlistRepository.getPlaylists().map {
                    browsable(BrowseIds.Node.Playlist(it.id), it.name)
                }
            BrowseIds.Node.Artists ->
                libraryRepository.observeAlbumArtists().first().map {
                    browsable(BrowseIds.Node.Artist(it.name), it.name)
                }
            BrowseIds.Node.Albums ->
                libraryRepository.observeAlbums().first().map { album ->
                    browsable(
                        BrowseIds.Node.Album(album.albumArtist, album.name),
                        album.name,
                        subtitle = album.albumArtist,
                        artworkPath = album.artworkTrackPath,
                    )
                }
            BrowseIds.Node.Genres ->
                libraryRepository.observeGenres().first().map {
                    browsable(BrowseIds.Node.Genre(it.name), it.name)
                }
            is BrowseIds.Node.Artist ->
                libraryRepository.observeAlbums(albumArtist = node.name).first().map { album ->
                    browsable(
                        BrowseIds.Node.Album(album.albumArtist, album.name),
                        album.name,
                        subtitle = album.albumArtist,
                        artworkPath = album.artworkTrackPath,
                    )
                }
            is BrowseIds.Node.Genre ->
                libraryRepository.observeAlbums(genre = node.name).first().map { album ->
                    browsable(
                        BrowseIds.Node.Album(album.albumArtist, album.name),
                        album.name,
                        subtitle = album.albumArtist,
                        artworkPath = album.artworkTrackPath,
                    )
                }
            is BrowseIds.Node.Album, is BrowseIds.Node.Playlist ->
                tracksFor(node).mapIndexed { i, track -> playable(track, node, index = i) }
            is BrowseIds.Node.Track -> emptyList()
        }
    }

    /**
     * The playable queue context of [node]: an album's tracks in disc/track
     * order, a playlist's tracks in stored order. Used both for listing
     * children and for expanding one tapped track into its full queue.
     */
    suspend fun tracksFor(node: BrowseIds.Node): List<Track> = when (node) {
        is BrowseIds.Node.Album ->
            libraryRepository.observeAlbumTracks(
                album = node.album,
                albumArtist = node.albumArtist,
                sort = SortSpec(SortKey.TRACK_NUMBER),
            ).first()
        is BrowseIds.Node.Playlist ->
            playlistRepository.getPlaylist(node.id)
                ?.let { playlistRepository.getPlaylistTracks(it) }
                .orEmpty()
        is BrowseIds.Node.Track -> tracksFor(node.parent)
        else -> emptyList()
    }

    // ---------------------------------------------------------------- builders

    private fun browsable(
        node: BrowseIds.Node,
        title: String,
        subtitle: String? = null,
        artworkPath: String? = null,
    ): MediaItem = MediaItem.Builder()
        .setMediaId(BrowseIds.encode(node))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(mediaType(node))
                .setArtworkUri(artworkPath?.let(MediaItems::artworkUri))
                .build(),
        )
        .build()

    /**
     * A playable leaf. No file URI on purpose: the id round-trips through the
     * controller, and onSetMediaItems rebuilds real queue items (with URIs)
     * from the library, exactly like every other enqueue path. [index] keeps
     * ids unique when a playlist holds the same track twice.
     */
    private fun playable(track: Track, parent: BrowseIds.Node, index: Int? = null): MediaItem = MediaItem.Builder()
        .setMediaId(BrowseIds.encode(BrowseIds.Node.Track(track.id, parent, index)))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.artist.ifBlank { track.effectiveAlbumArtist })
                .setAlbumTitle(track.album)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .setArtworkUri(
                    if (track.hasEmbeddedArt) MediaItems.artworkUri(track.filePath) else null,
                )
                .build(),
        )
        .build()

    private fun mediaType(node: BrowseIds.Node): Int = when (node) {
        is BrowseIds.Node.Album -> MediaMetadata.MEDIA_TYPE_ALBUM
        is BrowseIds.Node.Artist -> MediaMetadata.MEDIA_TYPE_ARTIST
        is BrowseIds.Node.Genre -> MediaMetadata.MEDIA_TYPE_GENRE
        is BrowseIds.Node.Playlist -> MediaMetadata.MEDIA_TYPE_PLAYLIST
        BrowseIds.Node.Playlists -> MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS
        BrowseIds.Node.Artists -> MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS
        BrowseIds.Node.Albums -> MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS
        BrowseIds.Node.Genres -> MediaMetadata.MEDIA_TYPE_FOLDER_GENRES
        else -> MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
    }
}
