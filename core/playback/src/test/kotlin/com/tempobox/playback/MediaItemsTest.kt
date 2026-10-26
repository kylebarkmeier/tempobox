package com.tempobox.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.tempobox.model.AudioFormat
import com.tempobox.model.ShuffleMode
import com.tempobox.model.Track
import org.junit.Test
import org.junit.runner.RunWith

/** Robolectric: MediaItem/Bundle/Uri need the Android framework. */
@RunWith(AndroidJUnit4::class)
class MediaItemsTest {

    private val track = Track(
        id = 42,
        filePath = "/music/Artist/Album/01 Song.flac",
        title = "Song",
        artist = "Artist",
        albumArtist = "Album Artist",
        album = "Album",
        genre = "Post-Rock",
        year = 2011,
        durationMs = 245_000,
        format = AudioFormat.FLAC,
        rating = 4,
        hasEmbeddedArt = true,
    )

    @Test
    fun `track survives the MediaItem round trip`() {
        val item = MediaItems.toMediaItem(track, uid = 7)
        val restored = MediaItems.toTrack(item)

        assertThat(item.mediaId).isEqualTo("7")
        assertThat(restored.id).isEqualTo(42)
        assertThat(restored.filePath).isEqualTo(track.filePath)
        assertThat(restored.title).isEqualTo("Song")
        assertThat(restored.artist).isEqualTo("Artist")
        assertThat(restored.albumArtist).isEqualTo("Album Artist")
        assertThat(restored.album).isEqualTo("Album")
        assertThat(restored.genre).isEqualTo("Post-Rock")
        assertThat(restored.year).isEqualTo(2011)
        assertThat(restored.durationMs).isEqualTo(245_000)
        assertThat(restored.format).isEqualTo(AudioFormat.FLAC)
        assertThat(restored.rating).isEqualTo(4)
        assertThat(restored.hasEmbeddedArt).isTrue()
    }

    @Test
    fun `queue item exposes the uid`() {
        val item = MediaItems.toMediaItem(track, uid = 99)
        val queueItem = MediaItems.toQueueItem(item)
        assertThat(queueItem.uid).isEqualTo(99)
        assertThat(queueItem.track.title).isEqualTo("Song")
    }

    @Test
    fun `artwork uri round-trips arbitrary paths`() {
        val path = "/music/Weird — name/ü & spaces/01.mp3"
        val uri = MediaItems.artworkUri(path)
        assertThat(uri.scheme).isEqualTo(MediaItems.ARTWORK_SCHEME)
        assertThat(MediaItems.pathFromArtworkUri(uri)).isEqualTo(path)
    }

    @Test
    fun `tracks without art get no artwork uri`() {
        val item = MediaItems.toMediaItem(track.copy(hasEmbeddedArt = false), uid = 1)
        assertThat(item.mediaMetadata.artworkUri).isNull()
    }

    @Test
    fun `blank track artist falls back to the album artist in metadata`() {
        val item = MediaItems.toMediaItem(track.copy(artist = ""), uid = 1)
        // What lockscreens/AVRCP display:
        assertThat(item.mediaMetadata.artist.toString()).isEqualTo("Album Artist")
        // And the restored queue track keeps the fallback as its artist.
        assertThat(MediaItems.toTrack(item).artist).isEqualTo("Album Artist")
    }

    @Test
    fun `missing year survives the round trip as null`() {
        val item = MediaItems.toMediaItem(track.copy(year = null), uid = 1)
        assertThat(MediaItems.toTrack(item).year).isNull()
    }

    @Test
    fun `unknown format string degrades to OTHER`() {
        val item = MediaItems.toMediaItem(track, uid = 1)
        val tampered = item.buildUpon()
            .setMediaMetadata(
                item.mediaMetadata.buildUpon()
                    .setExtras(
                        android.os.Bundle(item.mediaMetadata.extras).apply {
                            putString("format", "WAVPACK9000")
                        },
                    )
                    .build(),
            )
            .build()
        assertThat(MediaItems.toTrack(tampered).format).isEqualTo(AudioFormat.OTHER)
    }

    @Test
    fun `an item without extras maps to safe defaults`() {
        // E.g. a MediaItem offered by an external controller.
        val foreign = androidx.media3.common.MediaItem.Builder().setMediaId("not-a-uid").build()
        val queueItem = MediaItems.toQueueItem(foreign)
        assertThat(queueItem.uid).isEqualTo(-1) // unknown uid sentinel
        assertThat(queueItem.track.id).isEqualTo(0)
        assertThat(queueItem.track.filePath).isEmpty()
        assertThat(queueItem.track.format).isEqualTo(AudioFormat.OTHER)
    }

    @Test
    fun `a service shuffle mark survives the item round trip`() {
        val marked = MediaItems.toMediaItem(track, uid = 7, shuffled = MediaItems.Shuffled(ShuffleMode.ANTI_REPEAT, 3))
        assertThat(MediaItems.shuffled(marked)).isEqualTo(MediaItems.Shuffled(ShuffleMode.ANTI_REPEAT, 3))
        assertThat(MediaItems.shuffled(MediaItems.toMediaItem(track, uid = 8))).isNull()
        // The mark does not leak into the queue-facing track.
        assertThat(MediaItems.toTrack(marked)).isEqualTo(MediaItems.toTrack(MediaItems.toMediaItem(track, uid = 7)))
        // Another controller's extras with the same keys but no usable mode are no mark.
        val bogus = MediaItem.Builder()
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setExtras(
                        Bundle().apply {
                            putString("voiceShuffleMode", "SIDEWAYS")
                            putInt("voiceOriginalIndex", 0)
                        },
                    )
                    .build(),
            )
            .build()
        assertThat(MediaItems.shuffled(bogus)).isNull()
    }

    @Test
    fun `voiceQueue marks shuffled items with their pre-shuffle position and leaves an ordered queue unmarked`() {
        val tracks = listOf(track.copy(id = 1), track.copy(id = 2, title = "B"), track.copy(id = 3, title = "C"))
        var uid = 100L
        val shuffled = MediaItems.voiceQueue(tracks, order = listOf(2, 0, 1), mode = ShuffleMode.ALL) { uid++ }
        assertThat(shuffled.map { it.mediaId }).containsExactly("100", "101", "102").inOrder()
        assertThat(shuffled.map { MediaItems.toTrack(it).id }).containsExactly(3L, 1L, 2L).inOrder()
        assertThat(shuffled.map { MediaItems.shuffled(it) })
            .containsExactly(
                MediaItems.Shuffled(ShuffleMode.ALL, 2), MediaItems.Shuffled(ShuffleMode.ALL, 0), MediaItems.Shuffled(ShuffleMode.ALL, 1),
            )
            .inOrder()
        val ordered = MediaItems.voiceQueue(tracks, order = tracks.indices.toList(), mode = null) { uid++ }
        assertThat(ordered.map { MediaItems.toTrack(it).id }).containsExactly(1L, 2L, 3L).inOrder()
        assertThat(ordered.map { MediaItems.shuffled(it) }).containsExactly(null, null, null)
    }
}
