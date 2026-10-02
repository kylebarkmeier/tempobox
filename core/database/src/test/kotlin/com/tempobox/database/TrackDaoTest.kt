package com.tempobox.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.tempobox.database.entity.TrackEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** In-memory Room (Robolectric) tests for the track table & aggregates. */
@RunWith(AndroidJUnit4::class)
class TrackDaoTest {

    private lateinit var db: TempoBoxDatabase
    private lateinit var dao: com.tempobox.database.dao.TrackDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            TempoBoxDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.trackDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun entity(
        path: String,
        title: String = "T",
        artist: String = "Artist",
        albumArtist: String = "Album Artist",
        album: String = "Album",
        genre: String = "Rock",
        year: Int? = 2000,
        rating: Int = 0,
        playCount: Long = 0,
        added: Long = 1000,
        modified: Long = 1000,
        hasArt: Boolean = false,
    ) = TrackEntity(
        filePath = path, title = title, artist = artist, albumArtist = albumArtist,
        album = album, genre = genre, year = year, trackNumber = 1, discNumber = 1,
        durationMs = 180_000, format = "MP3", bitrateKbps = 320, sampleRateHz = 44100,
        sizeBytes = 1, rating = rating, playCount = playCount,
        dateAddedMs = added, dateModifiedMs = modified, hasEmbeddedArt = hasArt,
    )

    // ------------------------------------------------------------------ upsert

    @Test
    fun `upsert preserves rating, play count and dateAdded on rescan`() = runTest {
        dao.upsertKeepingUserData(listOf(entity("/a.mp3", title = "Old", added = 111)))
        dao.setRating(dao.getByPath("/a.mp3")!!.id, 5)
        dao.incrementPlayCount(dao.getByPath("/a.mp3")!!.id)

        // Rescan sees the same path with fresh metadata & scan-time dateAdded.
        dao.upsertKeepingUserData(listOf(entity("/a.mp3", title = "New", added = 999)))

        val after = dao.getByPath("/a.mp3")!!
        assertThat(after.title).isEqualTo("New")
        assertThat(after.rating).isEqualTo(5)
        assertThat(after.playCount).isEqualTo(1)
        assertThat(after.dateAddedMs).isEqualTo(111)
    }

    @Test
    fun `upsert inserts new paths`() = runTest {
        dao.upsertKeepingUserData(listOf(entity("/a.mp3"), entity("/b.mp3")))
        assertThat(dao.count()).isEqualTo(2)
    }

    @Test
    fun `upsert handles a mixed batch of new and known paths`() = runTest {
        dao.upsertKeepingUserData(listOf(entity("/a.mp3", title = "Old")))
        val existingId = dao.getByPath("/a.mp3")!!.id
        dao.setRating(existingId, 3)

        dao.upsertKeepingUserData(
            listOf(entity("/a.mp3", title = "Refreshed"), entity("/b.mp3", title = "Fresh")),
        )

        assertThat(dao.count()).isEqualTo(2)
        val a = dao.getByPath("/a.mp3")!!
        assertThat(a.id).isEqualTo(existingId) // identity is stable across rescans
        assertThat(a.title).isEqualTo("Refreshed")
        assertThat(a.rating).isEqualTo(3)
        assertThat(dao.getByPath("/b.mp3")!!.title).isEqualTo("Fresh")
    }

    // ------------------------------------------------------------------ lookups

    @Test
    fun `getByIds returns only the requested rows`() = runTest {
        dao.upsertKeepingUserData(listOf(entity("/a.mp3"), entity("/b.mp3"), entity("/c.mp3")))
        val wanted = listOf(dao.getByPath("/a.mp3")!!.id, dao.getByPath("/c.mp3")!!.id)
        assertThat(dao.getByIds(wanted).map { it.filePath }).containsExactly("/a.mp3", "/c.mp3")
    }

    @Test
    fun `incrementPlayCount accumulates`() = runTest {
        dao.upsertKeepingUserData(listOf(entity("/a.mp3")))
        val id = dao.getByPath("/a.mp3")!!.id
        dao.incrementPlayCount(id)
        dao.incrementPlayCount(id)
        assertThat(dao.getById(id)!!.playCount).isEqualTo(2)
    }

    @Test
    fun `getAllScanMeta exposes the scanner's diff columns`() = runTest {
        dao.upsertKeepingUserData(listOf(entity("/a.mp3", added = 7, modified = 42)))
        val meta = dao.getAllScanMeta().single()
        assertThat(meta.filePath).isEqualTo("/a.mp3")
        assertThat(meta.dateAddedMs).isEqualTo(7)
        assertThat(meta.dateModifiedMs).isEqualTo(42)
    }

    // ------------------------------------------------------------------ aggregates

    @Test
    fun `albums group by album and album artist`() = runTest {
        dao.upsertKeepingUserData(
            listOf(
                entity("/1.mp3", album = "A", albumArtist = "X", rating = 3, hasArt = true),
                entity("/2.mp3", album = "A", albumArtist = "X", rating = 5),
                entity("/3.mp3", album = "A", albumArtist = "Y"), // same title, other artist
            ),
        )
        val albums = dao.observeAlbums().first()
        assertThat(albums).hasSize(2)
        val albumX = albums.first { it.albumArtist == "X" }
        assertThat(albumX.trackCount).isEqualTo(2)
        assertThat(albumX.maxRating).isEqualTo(5)
        assertThat(albumX.artworkTrackPath).isEqualTo("/1.mp3")
    }

    @Test
    fun `album artist aggregate counts albums, tracks and genres`() = runTest {
        dao.upsertKeepingUserData(
            listOf(
                entity("/1.mp3", albumArtist = "X", album = "A", genre = "Rock"),
                entity("/2.mp3", albumArtist = "X", album = "B", genre = "Jazz"),
                entity("/3.mp3", albumArtist = "X", album = "B", genre = "Rock"),
            ),
        )
        val artists = dao.observeAlbumArtists().first()
        assertThat(artists).hasSize(1)
        val x = artists.single().toModel()
        assertThat(x.albumCount).isEqualTo(2)
        assertThat(x.trackCount).isEqualTo(3)
        assertThat(x.genres).containsExactly("Jazz", "Rock")
    }

    @Test
    fun `blank genre lands in the unknown-genre bucket`() = runTest {
        dao.upsertKeepingUserData(
            listOf(entity("/1.mp3", genre = ""), entity("/2.mp3", genre = "Rock")),
        )
        val genres = dao.observeGenres().first().map { it.name }
        assertThat(genres).containsExactly("Rock", "Unknown Genre")
    }

    @Test
    fun `genre filter applies to album artist aggregates`() = runTest {
        dao.upsertKeepingUserData(
            listOf(
                entity("/1.mp3", albumArtist = "Rocker", genre = "Rock"),
                entity("/2.mp3", albumArtist = "Jazzer", genre = "Jazz"),
            ),
        )
        val rockArtists = dao.observeAlbumArtists(genre = "Rock").first()
        assertThat(rockArtists.map { it.name }).containsExactly("Rocker")
    }

    @Test
    fun `recently added filter respects the cutoff`() = runTest {
        dao.upsertKeepingUserData(
            listOf(entity("/old.mp3", added = 100), entity("/new.mp3", added = 900)),
        )
        val recent = dao.observeRecentlyAdded(sinceMs = 500).first()
        assertThat(recent.map { it.filePath }).containsExactly("/new.mp3")
    }

    @Test
    fun `album aggregate filters by album artist, genre and cutoff`() = runTest {
        dao.upsertKeepingUserData(
            listOf(
                entity("/1.mp3", albumArtist = "X", album = "A", genre = "Rock", added = 100),
                entity("/2.mp3", albumArtist = "X", album = "B", genre = "Jazz", added = 900),
                entity("/3.mp3", albumArtist = "Y", album = "C", genre = "Rock", added = 900),
            ),
        )
        assertThat(dao.observeAlbums(albumArtist = "X").first().map { it.name })
            .containsExactly("A", "B")
        assertThat(dao.observeAlbums(genre = "Rock").first().map { it.name })
            .containsExactly("A", "C")
        assertThat(dao.observeAlbums(sinceMs = 500).first().map { it.name })
            .containsExactly("B", "C")
    }

    @Test
    fun `album aggregate sums duration and takes the max year`() = runTest {
        dao.upsertKeepingUserData(
            listOf(
                entity("/1.mp3", album = "A", year = 1999),
                entity("/2.mp3", album = "A", year = 2001),
            ),
        )
        val album = dao.observeAlbums().first().single()
        assertThat(album.durationMs).isEqualTo(360_000)
        assertThat(album.year).isEqualTo(2001)
    }

    @Test
    fun `track artist aggregate falls back to the album artist for blank artists`() = runTest {
        dao.upsertKeepingUserData(
            listOf(
                entity("/1.mp3", artist = "Solo", albumArtist = "Band"),
                entity("/2.mp3", artist = "", albumArtist = "Band"),
            ),
        )
        val names = dao.observeTrackArtists().first().map { it.name }
        assertThat(names).containsExactly("Band", "Solo")
    }

    @Test
    fun `genre album art groups per genre and album, summing play counts`() = runTest {
        dao.upsertKeepingUserData(
            listOf(
                entity("/r1.mp3", genre = "Rock", album = "A", playCount = 2, hasArt = true),
                entity("/r2.mp3", genre = "Rock", album = "A", playCount = 3, hasArt = false),
                entity("/r3.mp3", genre = "Rock", album = "B", playCount = 1, hasArt = true),
                entity("/j1.mp3", genre = "Jazz", album = "C", playCount = 7, hasArt = true),
            ),
        )
        val rows = dao.observeGenreAlbumArt().first()
        assertThat(rows).hasSize(3)
        val rockA = rows.single { it.genreName == "Rock" && it.album == "A" }
        assertThat(rockA.playCount).isEqualTo(5) // summed across the album's tracks
        assertThat(rockA.trackCount).isEqualTo(2)
        assertThat(rockA.artworkTrackPath).isEqualTo("/r1.mp3") // only the track WITH art
        assertThat(rows.single { it.genreName == "Jazz" }.artworkTrackPath).isEqualTo("/j1.mp3")
    }

    @Test
    fun `genre album art drops albums without any embedded art`() = runTest {
        dao.upsertKeepingUserData(
            listOf(
                entity("/bare1.mp3", genre = "Rock", album = "Bare", hasArt = false),
                entity("/bare2.mp3", genre = "Rock", album = "Bare", hasArt = false),
                entity("/art.mp3", genre = "Rock", album = "Covered", hasArt = true),
            ),
        )
        val rows = dao.observeGenreAlbumArt().first()
        assertThat(rows.map { it.album }).containsExactly("Covered")
    }

    @Test
    fun `genre album art buckets blank genres under the unknown genre`() = runTest {
        dao.upsertKeepingUserData(
            listOf(entity("/1.mp3", genre = "", album = "A", hasArt = true)),
        )
        val row = dao.observeGenreAlbumArt().first().single()
        assertThat(row.genreName).isEqualTo("Unknown Genre")
    }

    @Test
    fun `genre tracks query matches the unknown-genre bucket`() = runTest {
        dao.upsertKeepingUserData(
            listOf(entity("/1.mp3", genre = ""), entity("/2.mp3", genre = "Rock")),
        )
        val unknown = dao.observeGenreTracks("Unknown Genre").first()
        assertThat(unknown.map { it.filePath }).containsExactly("/1.mp3")
    }

    @Test
    fun `album tracks are ordered by disc then track number`() = runTest {
        dao.upsertKeepingUserData(
            listOf(
                entity("/d2t1.mp3", album = "A").copy(discNumber = 2, trackNumber = 1),
                entity("/d1t2.mp3", album = "A").copy(discNumber = 1, trackNumber = 2),
                entity("/d1t1.mp3", album = "A").copy(discNumber = 1, trackNumber = 1),
            ),
        )
        val ordered = dao.observeAlbumTracks("A", "Album Artist").first()
        assertThat(ordered.map { it.filePath })
            .containsExactly("/d1t1.mp3", "/d1t2.mp3", "/d2t1.mp3")
            .inOrder()
    }

    // ------------------------------------------------------------------ removal

    @Test
    fun `deleteByPaths removes exactly the given rows`() = runTest {
        dao.upsertKeepingUserData(listOf(entity("/a.mp3"), entity("/b.mp3")))
        dao.deleteByPaths(listOf("/a.mp3"))
        assertThat(dao.getAllPaths()).containsExactly("/b.mp3")
    }

    @Test
    fun `deleteAll empties the table`() = runTest {
        dao.upsertKeepingUserData(listOf(entity("/a.mp3")))
        dao.deleteAll()
        assertThat(dao.count()).isEqualTo(0)
    }
}
