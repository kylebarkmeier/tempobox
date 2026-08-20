package com.tempobox.playback

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.tempobox.model.AudioFormat
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
}
