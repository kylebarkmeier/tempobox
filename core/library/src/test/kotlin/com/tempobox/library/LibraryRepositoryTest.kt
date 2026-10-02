package com.tempobox.library

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.tempobox.database.TempoBoxDatabase
import com.tempobox.database.entity.toEntity
import com.tempobox.model.SortKey
import com.tempobox.model.SortSpec
import com.tempobox.model.TagData
import com.tempobox.model.Track
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
class LibraryRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: TempoBoxDatabase
    private lateinit var tagIO: FakeTagIO
    private lateinit var repository: LibraryRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            TempoBoxDatabase::class.java,
        ).allowMainThreadQueries().build()
        tagIO = FakeTagIO()
        repository = LibraryRepository(db.trackDao(), tagIO, UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seed(path: String, title: String = "T", rating: Int = 0): Long {
        db.trackDao().insert(
            Track(filePath = path, title = title, artist = "A", albumArtist = "A", rating = rating)
                .toEntity(),
        )
        return db.trackDao().getByPath(path)!!.id
    }

    @Test
    fun `setRating clamps to 0-5`() = runTest {
        val id = seed("/a.mp3")
        repository.setRating(id, 99)
        assertThat(db.trackDao().getById(id)!!.rating).isEqualTo(5)
        repository.setRating(id, -3)
        assertThat(db.trackDao().getById(id)!!.rating).isEqualTo(0)
    }

    @Test
    fun `getTracksByIds preserves the requested order`() = runTest {
        val a = seed("/a.mp3", title = "A")
        val b = seed("/b.mp3", title = "B")
        val c = seed("/c.mp3", title = "C")
        val result = repository.getTracksByIds(listOf(c, a, b))
        assertThat(result.map { it.id }).containsExactly(c, a, b).inOrder()
    }

    @Test
    fun `observeTracks honors the sort spec`() = runTest {
        seed("/1.mp3", title = "Zebra")
        seed("/2.mp3", title = "The Apple")
        val sorted = repository.observeTracks(SortSpec(SortKey.ALPHABETICAL)).first()
        assertThat(sorted.map { it.title }).containsExactly("The Apple", "Zebra").inOrder()
    }

    @Test
    fun `editTags writes files and re-syncs the database`() = runTest {
        val file = File(tmp.root, "A__X__Song.mp3").apply { writeText("x") }
        val id = seed(file.absolutePath, title = "Song", rating = 4)

        val failed = repository.editTags(listOf(id), TagData(genre = "Shoegaze", year = 1991))
        assertThat(failed).isEmpty()

        val updated = db.trackDao().getById(id)!!
        assertThat(updated.genre).isEqualTo("Shoegaze")
        assertThat(updated.year).isEqualTo(1991)
        assertThat(updated.rating).isEqualTo(4) // library data untouched
    }

    @Test
    fun `editTags reports unwritable files and leaves them unchanged`() = runTest {
        val file = File(tmp.root, "A__X__Locked.mp3").apply { writeText("x") }
        tagIO.failingPaths += file.absolutePath
        val id = seed(file.absolutePath, title = "Locked")

        val failed = repository.editTags(listOf(id), TagData(genre = "New"))
        assertThat(failed.map { it.id }).containsExactly(id)
        assertThat(db.trackDao().getById(id)!!.genre).isNotEqualTo("New")
    }

    @Test
    fun `removeFromLibrary drops rows but keeps files`() = runTest {
        val file = File(tmp.root, "keep.mp3").apply { writeText("x") }
        val id = seed(file.absolutePath)
        repository.removeFromLibrary(listOf(id))
        assertThat(db.trackDao().count()).isEqualTo(0)
        assertThat(file.exists()).isTrue()
    }

    @Test
    fun `deleteFromDevice removes files and rows`() = runTest {
        val file = File(tmp.root, "gone.mp3").apply { writeText("x") }
        val id = seed(file.absolutePath)
        val failed = repository.deleteFromDevice(listOf(id))
        assertThat(failed).isEmpty()
        assertThat(file.exists()).isFalse()
        assertThat(db.trackDao().count()).isEqualTo(0)
    }

    @Test
    fun `resetLibrary wipes all rows`() = runTest {
        seed("/a.mp3")
        seed("/b.mp3")
        repository.resetLibrary()
        assertThat(db.trackDao().count()).isEqualTo(0)
    }

    // ------------------------------------------------------------------ bulk edits & partial failures

    @Test
    fun `bulk editTags applies to every writable track and reports only failures`() = runTest {
        val ok = File(tmp.root, "A__X__Ok.mp3").apply { writeText("x") }
        val locked = File(tmp.root, "A__X__Locked.mp3").apply { writeText("x") }
        tagIO.failingPaths += locked.absolutePath
        val okId = seed(ok.absolutePath, title = "Ok")
        val lockedId = seed(locked.absolutePath, title = "Locked")

        val failed = repository.editTags(listOf(okId, lockedId), TagData(genre = "Ambient"))

        assertThat(failed.map { it.id }).containsExactly(lockedId)
        assertThat(db.trackDao().getById(okId)!!.genre).isEqualTo("Ambient")
        assertThat(db.trackDao().getById(lockedId)!!.genre).isNotEqualTo("Ambient")
    }

    @Test
    fun `deleteFromDevice keeps undeletable tracks in the library and reports them`() = runTest {
        // A non-empty directory can't be File.delete()d on any OS — a portable
        // stand-in for a file the app lacks write permission for.
        val undeletable = File(tmp.root, "stubborn").apply {
            mkdirs()
            File(this, "child.txt").writeText("x")
        }
        val gone = File(tmp.root, "gone.mp3").apply { writeText("x") }
        val stubbornId = seed(undeletable.absolutePath, title = "Stubborn")
        val goneId = seed(gone.absolutePath, title = "Gone")

        val failed = repository.deleteFromDevice(listOf(stubbornId, goneId))

        assertThat(failed.map { it.id }).containsExactly(stubbornId)
        assertThat(gone.exists()).isFalse()
        assertThat(db.trackDao().getById(goneId)).isNull()
        assertThat(db.trackDao().getById(stubbornId)).isNotNull() // kept so the UI can surface it
    }

    @Test
    fun `missing files count as deleted rather than failing`() = runTest {
        val id = seed("${tmp.root}/already-gone.mp3")
        val failed = repository.deleteFromDevice(listOf(id))
        assertThat(failed).isEmpty()
        assertThat(db.trackDao().count()).isEqualTo(0)
    }

    @Test
    fun `incrementPlayCount accumulates through the repository`() = runTest {
        val id = seed("/a.mp3")
        repository.incrementPlayCount(id)
        repository.incrementPlayCount(id)
        assertThat(db.trackDao().getById(id)!!.playCount).isEqualTo(2)
    }

    // ------------------------------------------------------------------ large libraries

    @Test
    fun `getTracksByIds spans the SQLite variable limit chunking`() = runTest {
        val entities = (1..501).map { i ->
            Track(filePath = "/bulk/$i.mp3", title = "T$i", artist = "A", albumArtist = "A").toEntity()
        }
        db.trackDao().upsertKeepingUserData(entities)
        val ids = db.trackDao().getAllPaths().map { db.trackDao().getByPath(it)!!.id }.shuffled()

        val result = repository.getTracksByIds(ids)
        assertThat(result.map { it.id }).isEqualTo(ids) // all found, caller order kept
    }

    // ------------------------------------------------------------------ aggregate sorting

    private suspend fun seedFull(
        path: String,
        title: String = "T",
        artist: String = "A",
        album: String = "Al",
        genre: String = "Rock",
        year: Int? = 2000,
        rating: Int = 0,
        added: Long = 0,
    ) {
        db.trackDao().insert(
            Track(
                filePath = path, title = title, artist = artist, albumArtist = artist,
                album = album, genre = genre, year = year, rating = rating, dateAddedMs = added,
            ).toEntity(),
        )
    }

    @Test
    fun `album artists sort article-aware alphabetically`() = runTest {
        seedFull("/1.mp3", artist = "Cream")
        seedFull("/2.mp3", artist = "The Beatles")
        seedFull("/3.mp3", artist = "Abba")

        val names = repository.observeAlbumArtists(sort = SortSpec(SortKey.ALPHABETICAL)).first()
            .map { it.name }
        assertThat(names).containsExactly("Abba", "The Beatles", "Cream").inOrder()
    }

    @Test
    fun `albums sort by rating uses the album's best-rated track`() = runTest {
        // Max and average ratings disagree on purpose: "Consistent" averages
        // higher (4.0 vs 3.0) but "OneHit" holds the better single track (5).
        // The spec'd order is by the album's BEST track, so OneHit wins.
        seedFull("/1.mp3", album = "Consistent", rating = 4)
        seedFull("/2.mp3", album = "Consistent", rating = 4)
        seedFull("/3.mp3", album = "OneHit", rating = 5)
        seedFull("/4.mp3", album = "OneHit", rating = 1)

        val sorted = repository.observeAlbums(sort = SortSpec(SortKey.RATING, ascending = false)).first()
        assertThat(sorted.map { it.name }).containsExactly("OneHit", "Consistent").inOrder()
    }

    @Test
    fun `albums sort by tag date orders on the year`() = runTest {
        seedFull("/1.mp3", album = "Nineties", year = 1995)
        seedFull("/2.mp3", album = "Eighties", year = 1985)
        seedFull("/3.mp3", album = "Untagged", year = null)

        val sorted = repository.observeAlbums(sort = SortSpec(SortKey.TAG_DATE, ascending = true)).first()
        assertThat(sorted.map { it.name })
            .containsExactly("Untagged", "Eighties", "Nineties")
            .inOrder()
    }

    @Test
    fun `genre rows carry the most played albums' artwork paths`() = runTest {
        suspend fun seedArt(path: String, album: String, genre: String, plays: Long, hasArt: Boolean = true) {
            db.trackDao().insert(
                Track(
                    filePath = path, title = path, artist = "A", albumArtist = "A",
                    album = album, genre = genre, playCount = plays, hasEmbeddedArt = hasArt,
                ).toEntity(),
            )
        }
        seedArt("/rock-rare.mp3", album = "Rare", genre = "Rock", plays = 1)
        seedArt("/rock-hit.mp3", album = "Hit", genre = "Rock", plays = 50)
        seedArt("/rock-bare.mp3", album = "Bare", genre = "Rock", plays = 99, hasArt = false)
        seedArt("/jazz.mp3", album = "Blue", genre = "Jazz", plays = 0)

        val genres = repository.observeGenres().first().associateBy { it.name }
        // Most played first; the artless album never contributes a slot.
        assertThat(genres.getValue("Rock").artworkTrackPaths)
            .containsExactly("/rock-hit.mp3", "/rock-rare.mp3")
            .inOrder()
        assertThat(genres.getValue("Jazz").artworkTrackPaths).containsExactly("/jazz.mp3")
    }

    @Test
    fun `genres sort by recently added descending`() = runTest {
        seedFull("/1.mp3", genre = "Old", added = 100)
        seedFull("/2.mp3", genre = "New", added = 900)

        val sorted = repository.observeGenres(sort = SortSpec(SortKey.RECENTLY_ADDED, ascending = false))
            .first()
        assertThat(sorted.map { it.name }).containsExactly("New", "Old").inOrder()
    }

    @Test
    fun `recently added tracks honor the cutoff and sort newest first`() = runTest {
        seedFull("/old.mp3", title = "Old", added = 100)
        seedFull("/mid.mp3", title = "Mid", added = 600)
        seedFull("/new.mp3", title = "New", added = 900)

        val recent = repository.observeRecentlyAddedTracks(sinceMs = 500).first()
        assertThat(recent.map { it.title }).containsExactly("New", "Mid").inOrder()
    }

    @Test
    fun `getTrackByPath resolves a single file`() = runTest {
        seed("/somewhere/a.mp3", title = "Found")
        assertThat(repository.getTrackByPath("/somewhere/a.mp3")!!.title).isEqualTo("Found")
        assertThat(repository.getTrackByPath("/nowhere.mp3")).isNull()
    }
}
