package com.tempobox.playback

import com.tempobox.common.DefaultDispatcher
import com.tempobox.library.LibraryRepository
import com.tempobox.library.PlaylistRepository
import com.tempobox.model.Album
import com.tempobox.model.AlbumArtist
import com.tempobox.model.Genre
import com.tempobox.model.Playlist
import com.tempobox.model.Track
import com.tempobox.model.searchKeyword
import com.tempobox.model.searchKeywords
import com.tempobox.model.wordPhraseMatcher
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
 * track title. Names compare in their [searchKeywords] form, so punctuation
 * the recognizer never produces ("AC/DC", "Don’t Stop") does not matter. A
 * "shuffle"/"shuffled" token anywhere turns the shuffle flag on. Fuzzy
 * matching for mis-recognized names is the planned follow-up.
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
            val buckets = Buckets(playlists)
            if (query.scope == Scope.LIBRARY) {
                // A playlist or artist literally named "My Music" or
                // "Everything" still wins, by its exact name only.
                val named = Query(query.plain, query.shuffle, Scope.ANY, plain = query.plain)
                resolveReading(named, exact = true, buckets, limitTo = NAMED_COLLECTIONS)
                    ?.let { return@withContext it }
                return@withContext wholeLibrary(query.shuffle)
            }
            if (query.text.searchKeywords().isEmpty()) return@withContext null
            val readings = readings(query)
            for (exact in listOf(true, false)) {
                for (reading in readings) {
                    resolveReading(reading, exact, buckets)?.let { return@withContext it }
                }
            }
            null
        }

    /**
     * The playlist [name] refers to: "my road trip playlist", "my road trip"
     * and "Road Trip" all find "Road Trip"; a playlist literally named "My
     * Playlist" is found by its full name when stripping leaves nothing.
     * Exact first, then whole words ("trip" finds "Road Trip", "end" does not
     * find "Weekend").
     */
    suspend fun findPlaylist(name: String, playlists: List<Playlist>? = null): Playlist? =
        withContext(dispatcher) {
            val query = parseQuery(name)
            val all = (playlists ?: playlistRepository.getPlaylists()).named { it.name }
            val bare = query.text.split(WHITESPACE).dropWhile { it.searchKeyword() in ARTICLES }.joinToString(" ")
            val candidates = listOf(query.text, bare, query.plain).map { it.searchKeywords() }
                .filter { it.isNotEmpty() }
                .distinct()
            for (q in candidates) all.firstOrNull { it.key == q }?.let { return@withContext it.item }
            // "my playlist" leaves the article "my" as a candidate; as a whole
            // word it would match "My Favorites", so only the exact pass may use it.
            val loose = candidates.filterNot { q -> q.split(' ').all { it in ARTICLES || it in FILLER || it == "playlist" } }
            for (q in loose) {
                val matches = wordPhraseMatcher(q)
                all.firstOrNull { matches(it.key) }?.let { return@withContext it.item }
            }
            null
        }

    /**
     * Every track, in library order, or unordered when the caller shuffles
     * anyway (the sort is the expensive part of a full load). Null for an
     * empty library.
     */
    suspend fun wholeLibrary(shuffle: Boolean): Resolution? = withContext(dispatcher) {
        val all = if (shuffle) libraryRepository.getTracks() else libraryRepository.observeTracks().first()
        if (all.isEmpty()) return@withContext null
        Resolution(all, shuffle, "whole library (${all.size} tracks)")
    }

    /**
     * As spoken with the type honored, then the same words in every bucket,
     * then the plain words with filler and type prefix put back; each also
     * read as "X by Y". [Buckets] remembers which (bucket, words) pairs a
     * pass already scanned, so the overlap between these readings costs
     * nothing.
     */
    private fun readings(query: Query): List<Query> {
        val bases = listOfNotNull(
            query,
            query.takeIf { it.scope != Scope.ANY }?.copy(scope = Scope.ANY),
            query.takeIf { it.text != it.plain }?.copy(text = query.plain, scope = Scope.ANY),
        )
        return bases.flatMap { listOfNotNull(it, splitByArtist(it)) }
    }

    /** One reading, one match strictness, across the buckets its scope allows (and [limitTo]). */
    private suspend fun resolveReading(
        reading: Query,
        exact: Boolean,
        b: Buckets,
        limitTo: Set<Scope> = ALL_BUCKETS,
    ): Resolution? {
        val q = reading.text.searchKeywords()
        if (q.isEmpty()) return null
        // A lone article or filler word ("my", "some") would whole-word
        // match half the library; only an exact name may be that short.
        if (!exact && q.split(' ').all { it in ARTICLES || it in FILLER }) return null
        val wanted = (if (reading.scope == Scope.ANY) ALL_BUCKETS else setOf(reading.scope)) intersect limitTo
        val artist = reading.artist?.searchKeywords()
        val buckets = if (artist == null) wanted else wanted intersect setOf(Scope.ALBUM, Scope.TRACK)
        // Keys are already folded; the matchers are compiled once per reading.
        val hit: (String) -> Boolean = if (exact) { key -> key == q } else wordPhraseMatcher(q)
        val artistMatches = artist?.let { wordPhraseMatcher(it) }
        val artistOk: (String) -> Boolean = { name ->
            artistMatches == null || artistMatches(name.searchKeywords())
        }
        fun fresh(scope: Scope) = scope in buckets && b.firstScan(scope, q, artist, exact)

        if (fresh(Scope.PLAYLIST)) {
            b.playlists().firstOrNull { hit(it.key) }?.item?.let { playlist ->
                val tracks = playlistRepository.getPlaylistTracks(playlist)
                if (tracks.isNotEmpty()) return Resolution(tracks, reading.shuffle, "playlist: ${playlist.name}")
            }
        }
        if (fresh(Scope.ARTIST)) {
            b.albumArtists().firstOrNull { hit(it.key) }?.item?.let { artist ->
                val tracks = libraryRepository.observeArtistTracks(artist.name).first()
                if (tracks.isNotEmpty()) return Resolution(tracks, reading.shuffle, "artist: ${artist.name}")
            }
            // Featured and compilation artists exist only as track artists.
            b.trackArtists().firstOrNull { hit(it.key) }?.item?.let { name ->
                val tracks = libraryRepository.observeTrackArtistTracks(name).first()
                if (tracks.isNotEmpty()) return Resolution(tracks, reading.shuffle, "artist: $name")
            }
        }
        if (fresh(Scope.ALBUM)) {
            b.albums().firstOrNull { hit(it.key) && artistOk(it.item.albumArtist) }?.item?.let { album ->
                val tracks = libraryRepository.observeAlbumTracks(album.name, album.albumArtist).first()
                if (tracks.isNotEmpty()) return Resolution(tracks, reading.shuffle, "album: ${album.name}")
            }
        }
        if (fresh(Scope.GENRE)) {
            b.genres().firstOrNull { hit(it.key) }?.item?.let { genre ->
                val tracks = libraryRepository.observeGenreTracks(genre.name).first()
                if (tracks.isNotEmpty()) return Resolution(tracks, reading.shuffle, "genre: ${genre.name}")
            }
        }
        if (fresh(Scope.TRACK)) {
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

    /** A named item with its match key, so each name is folded once per query. */
    private class Named<T>(val item: T, val key: String)

    /**
     * Per-query cache: each bucket loads at most once, and only when a
     * reading reaches it; [firstScan] is false for a (bucket, words) pair a
     * pass already scanned, since two readings often share their words.
     */
    private inner class Buckets(preloaded: List<Playlist>?) {
        private var playlists: List<Named<Playlist>>? = preloaded?.named { it.name }
        private var albumArtists: List<Named<AlbumArtist>>? = null
        private var trackArtists: List<Named<String>>? = null
        private var albums: List<Named<Album>>? = null
        private var genres: List<Named<Genre>>? = null
        private var tracks: List<Named<Track>>? = null
        private val scanned = HashSet<String>()

        fun firstScan(scope: Scope, q: String, artist: String?, exact: Boolean): Boolean =
            scanned.add("$scope|$exact|$q|$artist")

        suspend fun playlists() = playlists
            ?: playlistRepository.getPlaylists().named { it.name }.also { playlists = it }

        suspend fun albumArtists() = albumArtists
            ?: libraryRepository.observeAlbumArtists().first().named { it.name }.also { albumArtists = it }

        /** Names only: the Artists tab's aggregate (counts, artwork) is wasted here. */
        suspend fun trackArtists() = trackArtists
            ?: libraryRepository.getTrackArtistNames().named { it }.also { trackArtists = it }

        suspend fun albums() = albums
            ?: libraryRepository.observeAlbums().first().named { it.name }.also { albums = it }

        suspend fun genres() = genres
            ?: libraryRepository.observeGenres().first().named { it.name }.also { genres = it }

        /** Unsorted: only the first title match matters, and sorting is the costly part of a full load. */
        suspend fun tracks() = tracks
            ?: libraryRepository.getTracks().named { it.title }.also { tracks = it }
    }

    private fun <T> List<T>.named(name: (T) -> String): List<Named<T>> =
        map { Named(it, name(it).searchKeywords()) }

    companion object {
        private val WHITESPACE = Regex("\\s+")
        private val ALL_BUCKETS = setOf(Scope.PLAYLIST, Scope.ARTIST, Scope.ALBUM, Scope.GENRE, Scope.TRACK)

        /** Buckets a whole-library phrase is checked against before it means everything. */
        private val NAMED_COLLECTIONS = setOf(Scope.PLAYLIST, Scope.ARTIST)

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

        /** Requests for the whole library, in library order unless shuffle was said. */
        private val LIBRARY_PHRASES = setOf(
            "everything", "all", "all of it", "my music", "all my music", "all of my music",
            "my songs", "all my songs", "my library", "the library", "the whole library",
            "my whole library", "my collection", "my whole collection", "the whole collection",
        )

        /**
         * Requests for anything at all. They mean the whole library too, but
         * shuffled: nobody asking for "some music" wants it from A to Z.
         */
        private val VAGUE_PHRASES = setOf(
            "something", "anything", "anything at all", "music", "some music", "a song",
            "some songs", "any song",
        )

        private val APP_PREPOSITIONS = setOf("on", "in", "with", "from")

        /** The request as the user would recognize it: filler and type words kept, command words gone. */
        internal fun spokenName(raw: String): String = parseQuery(raw).plain.ifBlank { raw.trim() }

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
            val tail = keys().takeLast(3)
            val appName = when {
                words.size > 2 && tail.takeLast(2).let { it[0] in APP_PREPOSITIONS && it[1] == "tempobox" } -> 2
                words.size > 3 && tail[0] in APP_PREPOSITIONS && tail[1] == "tempo" && tail[2] == "box" -> 3
                else -> 0
            }
            words = words.dropLast(appName)
            val plain = words.joinToString(" ") { it.first }

            while (words.size > 1 && keys().first() in FILLER) words = words.drop(1)

            val phrase = keys().filter { it.isNotEmpty() }.joinToString(" ")
            if (phrase in LIBRARY_PHRASES) return Query("", shuffle, Scope.LIBRARY, plain = plain)
            if (phrase in VAGUE_PHRASES) return Query("", shuffle = true, Scope.LIBRARY, plain = plain)

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
