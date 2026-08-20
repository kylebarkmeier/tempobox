package com.tempobox.library

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.tempobox.database.TempoBoxDatabase
import com.tempobox.database.entity.toEntity
import com.tempobox.model.RuleField
import com.tempobox.model.RuleOp
import com.tempobox.model.SmartRule
import com.tempobox.model.Track
import com.tempobox.playlist.M3uCodec
import com.tempobox.playlist.SmartPlaylistEngine
import com.tempobox.settings.AppSettings
import com.tempobox.settings.LibrarySettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class PlaylistRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: TempoBoxDatabase
    private lateinit var repository: PlaylistRepository
    private lateinit var musicDir: File
    private val codec = M3uCodec()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            TempoBoxDatabase::class.java,
        ).allowMainThreadQueries().build()
        musicDir = tmp.newFolder("Music")
        repository = PlaylistRepository(
            context = ApplicationProvider.getApplicationContext(),
            playlistDao = db.playlistDao(),
            trackDao = db.trackDao(),
            codec = codec,
            engine = SmartPlaylistEngine(),
            settingsRepository = FakeSettingsRepository(
                AppSettings(library = LibrarySettings(locations = listOf(musicDir.absolutePath))),
            ),
            ioDispatcher = UnconfinedTestDispatcher(),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seedTrack(name: String, genre: String = "Rock", rating: Int = 0): Track {
        val file = File(musicDir, "$name.mp3").apply { writeText("x") }
        val track = Track(
            filePath = file.absolutePath, title = name, artist = "A", albumArtist = "A",
            album = "Al", genre = genre, rating = rating, durationMs = 60_000,
        )
        db.trackDao().insert(track.toEntity())
        val entity = db.trackDao().getByPath(file.absolutePath)!!
        return track.copy(id = entity.id)
    }

    // ------------------------------------------------------------------ static

    @Test
    fun `createPlaylist writes an m3u8 file into the Playlists folder`() = runTest {
        val t = seedTrack("song")
        val playlist = repository.createPlaylist("Road Trip", listOf(t.id))

        val file = File(playlist.filePath!!)
        assertThat(file.name).isEqualTo("Road Trip.m3u8")
        assertThat(file.parentFile!!.name).isEqualTo("Playlists")
        assertThat(codec.read(file)).containsExactly(t.filePath)
    }

    @Test
    fun `duplicate names get numbered`() = runTest {
        repository.createPlaylist("Mix")
        val second = repository.createPlaylist("Mix")
        assertThat(second.name).isEqualTo("Mix (2)")
    }

    @Test
    fun `addToPlaylist appends and rewrites the file`() = runTest {
        val a = seedTrack("a")
        val b = seedTrack("b")
        val playlist = repository.createPlaylist("Mix", listOf(a.id))
        repository.addToPlaylist(playlist.id, listOf(b.id))

        assertThat(codec.read(File(playlist.filePath!!)))
            .containsExactly(a.filePath, b.filePath)
            .inOrder()
    }

    @Test
    fun `deletePlaylist removes the row and its file`() = runTest {
        val playlist = repository.createPlaylist("Bye")
        val file = File(playlist.filePath!!)
        assertThat(file.exists()).isTrue()
        repository.deletePlaylist(playlist.id)
        assertThat(file.exists()).isFalse()
        assertThat(repository.getPlaylist(playlist.id)).isNull()
    }

    // ------------------------------------------------------------------ import

    @Test
    fun `importPlaylistFiles picks up an existing m3u and matches tracks`() = runTest {
        val t = seedTrack("known")
        // Legacy playlist referencing one known and one unknown path.
        File(musicDir, "old.m3u").writeText("known.mp3\nmissing.mp3\n")

        repository.importPlaylistFiles(listOf(musicDir.absolutePath))

        val imported = repository.observePlaylists().first().single { it.name == "old" }
        val tracks = repository.getPlaylistTracks(imported)
        assertThat(tracks.map { it.filePath }).containsExactly(t.filePath)
    }

    @Test
    fun `import is idempotent across rescans`() = runTest {
        File(musicDir, "old.m3u").writeText("")
        repository.importPlaylistFiles(listOf(musicDir.absolutePath))
        repository.importPlaylistFiles(listOf(musicDir.absolutePath))
        assertThat(repository.observePlaylists().first().filter { it.name.startsWith("old") })
            .hasSize(1)
    }

    // ------------------------------------------------------------------ smart

    @Test
    fun `smart playlists evaluate live against the library`() = runTest {
        seedTrack("rock1", genre = "Rock")
        seedTrack("jazz1", genre = "Jazz")
        val smart = repository.createSmartPlaylist(
            "Rock only",
            SmartRule.Condition(RuleField.GENRE, RuleOp.IS, "Rock"),
        )
        assertThat(repository.getPlaylistTracks(smart).map { it.title }).containsExactly("rock1")

        // New matching track appears without any manual update (spec).
        seedTrack("rock2", genre = "Rock")
        assertThat(repository.getPlaylistTracks(smart).map { it.title })
            .containsExactly("rock1", "rock2")
    }

    @Test
    fun `smart playlist stats update with the library`() = runTest {
        val smart = repository.createSmartPlaylist(
            "High rated",
            SmartRule.Condition(RuleField.RATING, RuleOp.GREATER_THAN, "3"),
        )
        assertThat(repository.observePlaylists().first().single { it.id == smart.id }.trackCount)
            .isEqualTo(0)
        seedTrack("fav", rating = 5)
        assertThat(repository.observePlaylists().first().single { it.id == smart.id }.trackCount)
            .isEqualTo(1)
    }

    @Test
    fun `refreshSmartExports writes the m3u8 snapshot`() = runTest {
        seedTrack("rock1", genre = "Rock")
        val smart = repository.createSmartPlaylist(
            "Rock",
            SmartRule.Condition(RuleField.GENRE, RuleOp.IS, "Rock"),
        )
        seedTrack("rock2", genre = "Rock")
        repository.refreshSmartExports()

        val exported = codec.read(File(smart.filePath!!))
        assertThat(exported).hasSize(2)
    }
}
