package com.tempobox.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.tempobox.database.entity.PlaylistEntity
import com.tempobox.database.entity.TrackEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaylistDaoTest {

    private lateinit var db: TempoBoxDatabase
    private lateinit var playlists: com.tempobox.database.dao.PlaylistDao
    private lateinit var tracks: com.tempobox.database.dao.TrackDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            TempoBoxDatabase::class.java,
        ).allowMainThreadQueries().build()
        playlists = db.playlistDao()
        tracks = db.trackDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seedTracks(count: Int): List<Long> {
        (1..count).forEach { i ->
            tracks.insert(
                TrackEntity(
                    filePath = "/t$i.mp3", title = "T$i", artist = "A", albumArtist = "A",
                    album = "Album", genre = "Rock", year = 2000, trackNumber = i,
                    discNumber = 1, durationMs = 60_000, format = "MP3", bitrateKbps = 320,
                    sampleRateHz = 44100, sizeBytes = 1, dateAddedMs = 0, dateModifiedMs = 0,
                ),
            )
        }
        return tracks.getAllPaths().sorted().map { tracks.getByPath(it)!!.id }
    }

    private fun playlistEntity(name: String, ruleJson: String? = null) = PlaylistEntity(
        name = name, filePath = "/playlists/$name.m3u8",
        smartRuleJson = ruleJson, dateAddedMs = 1, dateModifiedMs = 1,
    )

    @Test
    fun `entries keep explicit play order`() = runTest {
        val trackIds = seedTracks(3)
        val id = playlists.insert(playlistEntity("Mix"))
        playlists.replaceEntries(id, listOf(trackIds[2], trackIds[0], trackIds[1]))

        val ordered = playlists.getPlaylistTracks(id)
        assertThat(ordered.map { it.id })
            .containsExactly(trackIds[2], trackIds[0], trackIds[1])
            .inOrder()
    }

    @Test
    fun `append adds to the end`() = runTest {
        val trackIds = seedTracks(3)
        val id = playlists.insert(playlistEntity("Mix"))
        playlists.replaceEntries(id, listOf(trackIds[0]))
        playlists.appendEntries(id, listOf(trackIds[2]))

        assertThat(playlists.getPlaylistTracks(id).map { it.id })
            .containsExactly(trackIds[0], trackIds[2])
            .inOrder()
    }

    @Test
    fun `stats join reports count and duration`() = runTest {
        val trackIds = seedTracks(2)
        val id = playlists.insert(playlistEntity("Mix"))
        playlists.replaceEntries(id, trackIds)

        val stats = playlists.observeAllWithStats().first().single()
        assertThat(stats.trackCount).isEqualTo(2)
        assertThat(stats.durationMs).isEqualTo(120_000)
    }

    @Test
    fun `deleting a track cascades out of playlists`() = runTest {
        val trackIds = seedTracks(2)
        val id = playlists.insert(playlistEntity("Mix"))
        playlists.replaceEntries(id, trackIds)

        tracks.deleteByIds(listOf(trackIds[0]))
        assertThat(playlists.getPlaylistTracks(id).map { it.id }).containsExactly(trackIds[1])
    }

    @Test
    fun `deleting a playlist cascades its entries`() = runTest {
        val trackIds = seedTracks(1)
        val id = playlists.insert(playlistEntity("Mix"))
        playlists.replaceEntries(id, trackIds)
        playlists.delete(id)
        assertThat(playlists.getAll()).isEmpty()
        // Track itself is untouched.
        assertThat(tracks.count()).isEqualTo(1)
    }

    @Test
    fun `smart playlists are filtered by getSmartPlaylists`() = runTest {
        playlists.insert(playlistEntity("Static"))
        playlists.insert(playlistEntity("Smart", ruleJson = """{"kind":"condition"}"""))
        assertThat(playlists.getSmartPlaylists().map { it.name }).containsExactly("Smart")
    }

    @Test
    fun `getByName is case-insensitive`() = runTest {
        playlists.insert(playlistEntity("Road Trip"))
        assertThat(playlists.getByName("road trip")).isNotNull()
    }
}
