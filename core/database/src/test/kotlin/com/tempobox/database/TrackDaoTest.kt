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
