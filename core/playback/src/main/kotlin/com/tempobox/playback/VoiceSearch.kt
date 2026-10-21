package com.tempobox.playback

import com.tempobox.library.LibraryRepository
import com.tempobox.library.PlaylistRepository
import com.tempobox.model.Track
import com.tempobox.model.searchNormalized
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves a voice/search query ("Allicorn", "shuffle back in black") to a
 * playable queue. Fed by Assistant's play-from-search and Android Auto's
 * search screen through [PlaybackService]'s session callback.
 *
 * First cut: normalized exact-then-substring matching, bucket priority
 * playlist > artist > album > genre > track title (an exact match in any
 * bucket beats a substring match in every bucket). A "shuffle"/"shuffled"
 * token anywhere turns the shuffle flag on and leaves the rest as the query.
 * Fuzzy matching for mis-recognized names is the planned follow-up.
 */
@Singleton
class VoiceSearch @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val playlistRepository: PlaylistRepository,
) {

    /** [description] names what matched, for logs ("artist: Allicorn"). */
    data class Resolution(
        val tracks: List<Track>,
        val shuffle: Boolean,
        val description: String,
    )

    /** Null when the query is blank (after stripping) or nothing matches. */
    suspend fun resolve(rawQuery: String): Resolution? {
        val (query, shuffle) = stripCommandWords(rawQuery)
        val q = query.searchNormalized()
        if (q.isEmpty()) return null

        // Load each bucket once, tracks only if everything else missed.
        val playlists = playlistRepository.getPlaylists()
        val artists = libraryRepository.observeAlbumArtists().first()
        val albums = libraryRepository.observeAlbums().first()
        val genres = libraryRepository.observeGenres().first()

        for (matches in listOf<(String) -> Boolean>(
            { it.searchNormalized() == q },
            { it.searchNormalized().contains(q) },
        )) {
            playlists.firstOrNull { matches(it.name) }?.let { playlist ->
                val tracks = playlistRepository.getPlaylistTracks(playlist)
                if (tracks.isNotEmpty()) {
                    return Resolution(tracks, shuffle, "playlist: ${playlist.name}")
                }
            }
            artists.firstOrNull { matches(it.name) }?.let { artist ->
                val tracks = libraryRepository.observeArtistTracks(artist.name).first()
                if (tracks.isNotEmpty()) {
                    return Resolution(tracks, shuffle, "artist: ${artist.name}")
                }
            }
            albums.firstOrNull { matches(it.name) }?.let { album ->
                val tracks = libraryRepository
                    .observeAlbumTracks(album.name, album.albumArtist).first()
                if (tracks.isNotEmpty()) {
                    return Resolution(tracks, shuffle, "album: ${album.name}")
                }
            }
            genres.firstOrNull { matches(it.name) }?.let { genre ->
                val tracks = libraryRepository.observeGenreTracks(genre.name).first()
                if (tracks.isNotEmpty()) {
                    return Resolution(tracks, shuffle, "genre: ${genre.name}")
                }
            }
            libraryRepository.observeTracks().first()
                .firstOrNull { matches(it.title) }
                ?.let { track ->
                    return Resolution(listOf(track), shuffle, "track: ${track.title}")
                }
        }
        return null
    }

    /**
     * Strips "shuffle"/"shuffled" (setting the flag) and a leading "play",
     * which some assistants leave in the query text.
     */
    private fun stripCommandWords(raw: String): Pair<String, Boolean> {
        var shuffle = false
        val kept = raw.trim().split(Regex("\\s+")).filter { word ->
            when (word.searchNormalized()) {
                "shuffle", "shuffled" -> {
                    shuffle = true
                    false
                }
                else -> true
            }
        }
        val words = if (kept.firstOrNull()?.searchNormalized() == "play") kept.drop(1) else kept
        return words.joinToString(" ") to shuffle
    }
}
