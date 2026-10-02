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

    @Test
    fun `scan walks nested folders`() = runTest {
        val nested = File(musicDir, "Artist/Album").apply { mkdirs() }
        newAudioFile("A__X__Deep.mp3", dir = nested)
        newAudioFile("A__X__Top.mp3")

        val result = scanner.scan(listOf(musicDir.absolutePath))
        assertThat(result.added).isEqualTo(2)
    }

    @Test
    fun `scan merges multiple locations and ignores missing ones`() = runTest {
        val second = tmp.newFolder("Podcasts")
        newAudioFile("A__X__One.mp3")
        newAudioFile("B__Y__Two.mp3", dir = second)

        val result = scanner.scan(
            listOf(
                musicDir.absolutePath,
                second.absolutePath,
                File(tmp.root, "does-not-exist").absolutePath,
            ),
        )
        assertThat(result.added).isEqualTo(2)
    }

    @Test
    fun `overlapping locations do not duplicate tracks`() = runTest {
        val sub = File(musicDir, "rock").apply { mkdirs() }
        newAudioFile("A__X__One.mp3", dir = sub)

        // The sub-folder is listed both directly and via its parent.
        val result = scanner.scan(listOf(musicDir.absolutePath, sub.absolutePath))
        assertThat(result.added).isEqualTo(1)
        assertThat(db.trackDao().count()).isEqualTo(1)
    }

    @Test
    fun `a moved file is one removal plus one addition`() = runTest {
        val old = newAudioFile("A__X__One.mp3")
        scanner.scan(listOf(musicDir.absolutePath))

        val renamed = File(musicDir, "A__X__Renamed.mp3")
        old.renameTo(renamed)
        val result = scanner.scan(listOf(musicDir.absolutePath))

        assertThat(result.added).isEqualTo(1)
        assertThat(result.removed).isEqualTo(1)
        assertThat(db.trackDao().getAllPaths()).containsExactly(renamed.absolutePath)
    }

    @Test
    fun `scan leaves its state flow on the final Done`() = runTest {
        newAudioFile("A__X__One.mp3")
        val done = scanner.scan(listOf(musicDir.absolutePath))
        assertThat(scanner.state.value).isEqualTo(done)
        assertThat(done).isEqualTo(ScanState.Done(added = 1, updated = 0, removed = 0))
    }

    @Test
    fun `scanPaths ignores unsupported files`() = runTest {
        val art = File(musicDir, "cover.jpg").apply { writeText("not audio") }
        scanner.scanPaths(listOf(art.absolutePath))
        assertThat(db.trackDao().count()).isEqualTo(0)
    }

    @Test
    fun `scanPaths keeps user data for an already known file`() = runTest {
        val file = newAudioFile("A__X__One.mp3")
        scanner.scan(listOf(musicDir.absolutePath))
        val before = db.trackDao().getByPath(file.absolutePath)!!
        db.trackDao().setRating(before.id, 5)

        // The watcher re-reports the file (e.g. retagged in place).
        file.writeText("retagged")
        scanner.scanPaths(listOf(file.absolutePath))

        val after = db.trackDao().getByPath(file.absolutePath)!!
        assertThat(after.id).isEqualTo(before.id)
        assertThat(after.rating).isEqualTo(5)
        assertThat(after.dateAddedMs).isEqualTo(before.dateAddedMs) // not re-stamped
    }

    @Test
    fun `scanPaths picks up a brand new file without a full walk`() = runTest {
        val fresh = newAudioFile("A__X__Fresh.mp3")
        scanner.scanPaths(listOf(fresh.absolutePath))
        assertThat(db.trackDao().getAllPaths()).containsExactly(fresh.absolutePath)
    }
}
