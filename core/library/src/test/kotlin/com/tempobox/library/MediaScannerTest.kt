package com.tempobox.library

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.tempobox.database.TempoBoxDatabase
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class MediaScannerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: TempoBoxDatabase
    private lateinit var tagIO: FakeTagIO
    private lateinit var scanner: MediaScanner
    private lateinit var musicDir: File

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            TempoBoxDatabase::class.java,
        ).allowMainThreadQueries().build()
        tagIO = FakeTagIO()
        scanner = MediaScanner(db.trackDao(), tagIO, UnconfinedTestDispatcher())
        musicDir = tmp.newFolder("Music")
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun newAudioFile(name: String, dir: File = musicDir): File =
        File(dir, name).apply { writeText("fake audio: $name") }

    @Test
    fun `scan picks up supported files and skips others`() = runTest {
        newAudioFile("A__X__One.mp3")
        newAudioFile("A__X__Two.flac")
        newAudioFile("A__X__Three.ogg")
        newAudioFile("A__X__Four.m4a")
        newAudioFile("cover.jpg")
        newAudioFile("notes.txt")

        val result = scanner.scan(listOf(musicDir.absolutePath))
        assertThat(result.added).isEqualTo(4)
        assertThat(db.trackDao().count()).isEqualTo(4)
    }

    @Test
    fun `unchanged files are not re-read on rescan (efficiency)`() = runTest {
        newAudioFile("A__X__One.mp3")
        scanner.scan(listOf(musicDir.absolutePath))
        val readsAfterFirst = tagIO.readCount

        val second = scanner.scan(listOf(musicDir.absolutePath))
        assertThat(second.added).isEqualTo(0)
        assertThat(second.updated).isEqualTo(0)
        assertThat(tagIO.readCount).isEqualTo(readsAfterFirst) // zero extra tag reads
    }

    @Test
    fun `modified files are re-read`() = runTest {
        val file = newAudioFile("A__X__One.mp3")
        scanner.scan(listOf(musicDir.absolutePath))

        file.writeText("changed bytes so mtime/size differ")
        file.setLastModified(file.lastModified() + 5_000)
        val result = scanner.scan(listOf(musicDir.absolutePath))
        assertThat(result.updated).isEqualTo(1)
    }

    @Test
    fun `deleted files are pruned from the database`() = runTest {
        val keep = newAudioFile("A__X__Keep.mp3")
        val gone = newAudioFile("A__X__Gone.mp3")
        scanner.scan(listOf(musicDir.absolutePath))

        gone.delete()
        val result = scanner.scan(listOf(musicDir.absolutePath))
        assertThat(result.removed).isEqualTo(1)
        assertThat(db.trackDao().getAllPaths()).containsExactly(keep.absolutePath)
    }

    @Test
    fun `rescan preserves rating and play count (user data)`() = runTest {
        val file = newAudioFile("A__X__One.mp3")
        scanner.scan(listOf(musicDir.absolutePath))
        val id = db.trackDao().getByPath(file.absolutePath)!!.id
        db.trackDao().setRating(id, 4)
        db.trackDao().incrementPlayCount(id)

        file.writeText("retagged")
        file.setLastModified(file.lastModified() + 5_000)
        scanner.scan(listOf(musicDir.absolutePath))

        val after = db.trackDao().getByPath(file.absolutePath)!!
        assertThat(after.rating).isEqualTo(4)
        assertThat(after.playCount).isEqualTo(1)
    }

    @Test
    fun `nomedia folders are skipped`() = runTest {
        val hidden = File(musicDir, "ringtones").apply { mkdirs() }
        File(hidden, ".nomedia").writeText("")
        newAudioFile("A__X__Ring.mp3", dir = hidden)
        newAudioFile("A__X__Song.mp3")

        scanner.scan(listOf(musicDir.absolutePath))
        assertThat(db.trackDao().count()).isEqualTo(1)
    }

    @Test
    fun `unreadable files are skipped without failing the scan`() = runTest {
        val bad = newAudioFile("A__X__Corrupt.mp3")
        tagIO.failingPaths += bad.absolutePath
        newAudioFile("A__X__Good.mp3")

        val result = scanner.scan(listOf(musicDir.absolutePath))
        assertThat(result.added).isEqualTo(1)
    }

    @Test
    fun `scanPaths updates only the given files`() = runTest {
        val a = newAudioFile("A__X__One.mp3")
        val b = newAudioFile("A__X__Two.mp3")
        scanner.scan(listOf(musicDir.absolutePath))

        b.delete()
        scanner.scanPaths(listOf(a.absolutePath, b.absolutePath))
        assertThat(db.trackDao().getAllPaths()).containsExactly(a.absolutePath)
    }
}
