package com.tempobox.playback

import com.tempobox.common.DefaultDispatcher
import com.tempobox.library.LibraryRepository
import com.tempobox.library.PlaylistRepository
import com.tempobox.model.Album
import com.tempobox.model.AlbumArtist
import com.tempobox.model.Genre
import com.tempobox.model.Playlist
import com.tempobox.model.Track
import com.tempobox.model.containsWordPhrase
import com.tempobox.model.searchKeyword
import com.tempobox.model.searchNormalized
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves a voice/search query ("Allicorn", "shuffle back in black",
 * "songs by lamb of god", "thunderstruck by ac/dc", "everything") to a track
 * list. Fed by Assistant's play-from-search and Android Auto's search screen
 * through [PlaybackService]'s session callback, and by the in-app commands
 * through [VoiceActions].
 *
 * A query is read several ways, in order: as spoken with its type prefix
 * honored ("the artist X" searches artists only), then as "X by Y" (titles
 * and albums whose artist matches Y), then with nothing peeled off at all,
 * so a name that happens to start with a type word ("The Album Leaf",
 * "Songs from the Big Chair") still resolves. Every reading is tried for an
 * exact name match before any reading is tried for a whole-word match, so a
 * loose reading never beats a name that matches as spoken ("Stand By Me"
 * beats "The Last Stand" by Megadeth). Bucket priority within a reading is
 * playlist > artist (album artists, then track artists) > album > genre >
 * track title. A "shuffle"/"shuffled" token anywhere turns the shuffle flag
 * on. Fuzzy matching for mis-recognized names is the planned follow-up.
 */
@Singleton
class VoiceSearch @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val playlistRepository: PlaylistRepository,
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) {

    enum class Scope { ANY, PLAYLIST, ARTIST, ALBUM, GENRE, TRACK, LIBRARY }

    /**
     * The spoken request after command words are peeled off. [text] has the
     * type prefix and filler removed; [plain] keeps them (only "play", the
     * shuffle word and a trailing "on TempoBox" are gone) for the fallback
     * reading.
     */
    data class Query(
        val text: String,
        val shuffle: Boolean,
        val scope: Scope,
        /** From "X by Y": only albums and titles whose artist matches this. */
        val artist: String? = null,
        val plain: String = text,
    )

    /** [description] names what matched, for logs and toasts ("artist: Allicorn"). */
    data class Resolution(
        val tracks: List<Track>,
        val shuffle: Boolean,
        val description: String,
    )

    /**
     * Null when the query is blank (after stripping) or nothing matches.
     * [playlists] lets a caller that already loaded them skip the reload.
     */
    suspend fun resolve(rawQuery: String, playlists: List<Playlist>? = null): Resolution? =
        withContext(dispatcher) {
            val query = parseQuery(rawQuery)
            if (query.scope == Scope.LIBRARY) return@withContext wholeLibrary(query.shuffle)
            if (query.text.searchNormalized().isBlank()) return@withContext null
            val buckets = Buckets(playlists)
            val readings = readings(query)
            for (exact in listOf(true, false)) {
                for (reading in readings) {
                    resolveReading(reading, exact, buckets)?.let { return@withContext it }
                }
            }
            null
        }

    /**
     * The playlist [name] refers to: "my road trip playlist" and "Road Trip"
     * both find "Road Trip"; a playlist literally named "My Playlist" is found
     * by its full name when stripping leaves nothing. Exact first, then whole
     * words ("trip" finds "Road Trip", "end" does not find "Weekend").
     */
    suspend fun findPlaylist(name: String, playlists: List<Playlist>? = null): Playlist? =
        withContext(dispatcher) {
            val query = parseQuery(name)
            val all = playlists ?: playlistRepository.getPlaylists()
            val candidates = listOf(query.text, query.plain).map { it.searchNormalized().trim() }
                .filter { it.isNotEmpty() }
                .distinct()
            for (q in candidates) all.firstOrNull { it.name.searchNormalized() == q }?.let { return@withContext it }
            for (q in candidates) all.firstOrNull { it.name.containsWordPhrase(q) }?.let { return@withContext it }
            null
        }

    private suspend fun wholeLibrary(shuffle: Boolean): Resolution? {
        val all = libraryRepository.observeTracks().first()
        if (all.isEmpty()) return null
        return Resolution(all, shuffle, "whole library (${all.size} tracks)")
    }

    /**
     * As spoken with the type honored, then the same words in every bucket,
     * then the plain words with filler and type prefix put back; each also
     * read as "X by Y".
     */
    private fun readings(query: Query): List<Query> {
        val bases = listOfNotNull(
            query,
            query.takeIf { it.scope != Scope.ANY }?.copy(scope = Scope.ANY),
            query.takeIf { it.text != it.plain }?.copy(text = query.plain, scope = Scope.ANY),
        )
        return bases.flatMap { listOfNotNull(it, splitByArtist(it)) }.distinct()
    }

    /** One reading, one match strictness, across the buckets its scope allows. */
    private suspend fun resolveReading(reading: Query, exact: Boolean, b: Buckets): Resolution? {
        val q = reading.text.searchNormalized().trim()
        if (q.isEmpty()) return null
        val wanted = if (reading.scope == Scope.ANY) ALL_BUCKETS else setOf(reading.scope)
        val buckets = if (reading.artist == null) wanted else wanted intersect setOf(Scope.ALBUM, Scope.TRACK)
        val hit: (String) -> Boolean = if (exact) { key -> key == q } else { key -> key.containsWordPhrase(q) }
        val artistOk: (String) -> Boolean = { name ->
            reading.artist == null || name.containsWordPhrase(reading.artist)
        }

        if (Scope.PLAYLIST in buckets) {
            b.playlists().firstOrNull { hit(it.key) }?.item?.let { playlist ->
                val tracks = playlistRepository.getPlaylistTracks(playlist)
                if (tracks.isNotEmpty()) return Resolution(tracks, reading.shuffle, "playlist: ${playlist.name}")
            }
        }
        if (Scope.ARTIST in buckets) {
            b.albumArtists().firstOrNull { hit(it.key) }?.item?.let { artist ->
                val tracks = libraryRepository.observeArtistTracks(artist.name).first()
                if (tracks.isNotEmpty()) return Resolution(tracks, reading.shuffle, "artist: ${artist.name}")
            }
            // Featured and compilation artists exist only as track artists.
            b.trackArtists().firstOrNull { hit(it.key) }?.item?.let { artist ->
                val tracks = libraryRepository.observeTrackArtistTracks(artist.name).first()
                if (tracks.isNotEmpty()) return Resolution(tracks, reading.shuffle, "artist: ${artist.name}")
            }
        }
        if (Scope.ALBUM in buckets) {
            b.albums().firstOrNull { hit(it.key) && artistOk(it.item.albumArtist) }?.item?.let { album ->
                val tracks = libraryRepository.observeAlbumTracks(album.name, album.albumArtist).first()
                if (tracks.isNotEmpty()) return Resolution(tracks, reading.shuffle, "album: ${album.name}")
            }
        }
        if (Scope.GENRE in buckets) {
            b.genres().firstOrNull { hit(it.key) }?.item?.let { genre ->
                val tracks = libraryRepository.observeGenreTracks(genre.name).first()
                if (tracks.isNotEmpty()) return Resolution(tracks, reading.shuffle, "genre: ${genre.name}")
            }
        }
        if (Scope.TRACK in buckets) {
            b.tracks().firstOrNull { hit(it.key) && (artistOk(it.item.artist) || artistOk(it.item.albumArtist)) }
                ?.let { track ->
                    return Resolution(listOf(track.item), reading.shuffle, "track: ${track.item.title} by ${track.item.artist}")
                }
        }
        return null
    }

    /** "thunderstruck by ac/dc" splits at the last "by"; null when there is none to split at. */
    private fun splitByArtist(query: Query): Query? {
        if (query.scope !in setOf(Scope.ANY, Scope.ALBUM, Scope.TRACK)) return null
        val tokens = query.text.split(WHITESPACE).filter { it.isNotEmpty() }
        val at = tokens.indexOfLast { it.searchKeyword() == "by" }
        if (at <= 0 || at == tokens.lastIndex) return null
        return query.copy(
            text = tokens.subList(0, at).joinToString(" "),
            artist = tokens.subList(at + 1, tokens.size).joinToString(" "),
        )
    }

    /** A named item with its match key, so each name is normalized once per query. */
    private class Named<T>(val item: T, val key: String)

    /** Per-query cache: each bucket loads at most once, and only when a reading reaches it. */
    private inner class Buckets(preloaded: List<Playlist>?) {
        private var playlists: List<Named<Playlist>>? = preloaded?.named { it.name }
        private var albumArtists: List<Named<AlbumArtist>>? = null
        private var trackArtists: List<Named<AlbumArtist>>? = null
        private var albums: List<Named<Album>>? = null
        private var genres: List<Named<Genre>>? = null
        private var tracks: List<Named<Track>>? = null

        suspend fun playlists() = playlists
            ?: playlistRepository.getPlaylists().named { it.name }.also { playlists = it }

        suspend fun albumArtists() = albumArtists
            ?: libraryRepository.observeAlbumArtists().first().named { it.name }.also { albumArtists = it }

        suspend fun trackArtists() = trackArtists
            ?: libraryRepository.observeTrackArtists().first().named { it.name }.also { trackArtists = it }

        suspend fun albums() = albums
            ?: libraryRepository.observeAlbums().first().named { it.name }.also { albums = it }

        suspend fun genres() = genres
            ?: libraryRepository.observeGenres().first().named { it.name }.also { genres = it }

        suspend fun tracks() = tracks
            ?: libraryRepository.observeTracks().first().named { it.title }.also { tracks = it }
    }

    private fun <T> List<T>.named(name: (T) -> String): List<Named<T>> =
        map { Named(it, name(it).searchNormalized()) }

    companion object {
        private val WHITESPACE = Regex("\\s+")
        private val ALL_BUCKETS = setOf(Scope.PLAYLIST, Scope.ARTIST, Scope.ALBUM, Scope.GENRE, Scope.TRACK)

        /** Words that only add noise in front of a name ("play me some X"). */
        private val FILLER = setOf("some", "me")

        private val ARTICLES = setOf("the", "my", "a", "an")

        /** One type word, optionally after an article: "the artist X", "album X". */
        private val TYPE_WORDS = mapOf(
            "artist" to Scope.ARTIST, "band" to Scope.ARTIST,
            "album" to Scope.ALBUM, "record" to Scope.ALBUM,
            "playlist" to Scope.PLAYLIST,
            "genre" to Scope.GENRE,
            "song" to Scope.TRACK, "track" to Scope.TRACK,
        )

        /** "songs by X", "music by X", "songs from X": the artist. */
        private val BY_WORDS = setOf("songs", "music", "tracks", "everything", "something", "stuff", "albums")

        /** Requests for the whole library. */
        private val LIBRARY_PHRASES = setOf(
            "everything", "anything", "something", "all", "all of it", "music", "some music",
            "my music", "all my music", "my songs", "all my songs", "my library",
            "the library", "the whole library", "my whole library", "my collection",
            "my whole collection", "the whole collection",
        )

        private val APP_PREPOSITIONS = setOf("on", "in", "with", "from")

        /**
         * Strips "shuffle"/"shuffled" (setting the flag), a leading "play", a
         * trailing "on TempoBox" some assistants leave in, then filler and a
         * spoken type prefix or trailing "playlist". The remaining words keep
         * their spoken spelling; [Query.plain] keeps the filler and type words.
         */
        internal fun parseQuery(raw: String): Query {
            var shuffle = false
            var words = raw.trim().split(WHITESPACE).filter { it.isNotEmpty() }
                .map { it to it.searchKeyword() }
                .filter { (_, key) ->
                    if (key == "shuffle" || key == "shuffled") {
                        shuffle = true
                        false
                    } else {
                        true
                    }
                }
            fun keys() = words.map { it.second }

            if (keys().firstOrNull() == "play") words = words.drop(1)
            val appName = when {
                keys().size > 2 && keys()[keys().lastIndex - 1] in APP_PREPOSITIONS &&
                    keys().last() == "tempobox" -> 2
                keys().size > 3 && keys()[keys().lastIndex - 2] in APP_PREPOSITIONS &&
                    keys()[keys().lastIndex - 1] == "tempo" && keys().last() == "box" -> 3
                else -> 0
            }
            words = words.dropLast(appName)
            val plain = words.joinToString(" ") { it.first }

            val phrase = keys().filter { it.isNotEmpty() }.joinToString(" ")
            if (phrase in LIBRARY_PHRASES) return Query("", shuffle, Scope.LIBRARY, plain = plain)

            while (words.size > 1 && keys().first() in FILLER) words = words.drop(1)

            var scope = Scope.ANY
            val k = keys()
            when {
                k.size > 2 && k[0] in BY_WORDS && (k[1] == "by" || (k[0] == "songs" && k[1] == "from")) -> {
                    scope = Scope.ARTIST
                    words = words.drop(2)
                }
                k.size > 2 && k[0] in ARTICLES && k[1] in TYPE_WORDS -> {
                    scope = TYPE_WORDS.getValue(k[1])
                    words = words.drop(2)
                }
                k.size > 1 && k[0] in TYPE_WORDS -> {
                    scope = TYPE_WORDS.getValue(k[0])
                    words = words.drop(1)
                }
            }
            if (scope == Scope.ANY && words.size > 1 && keys().last() == "playlist") {
                scope = Scope.PLAYLIST
                words = words.dropLast(1)
                while (words.size > 1 && keys().first() in ARTICLES) words = words.drop(1)
            }
            return Query(words.joinToString(" ") { it.first }, shuffle, scope, plain = plain)
        }
    }
}
