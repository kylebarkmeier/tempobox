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
}
