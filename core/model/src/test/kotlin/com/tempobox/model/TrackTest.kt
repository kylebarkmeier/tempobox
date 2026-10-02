package com.tempobox.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TrackTest {

    @Test
    fun `effectiveAlbumArtist falls back to artist then placeholder`() {
        assertThat(Track(filePath = "/a", title = "t", albumArtist = "AA", artist = "A").effectiveAlbumArtist)
            .isEqualTo("AA")
        assertThat(Track(filePath = "/a", title = "t", albumArtist = "", artist = "A").effectiveAlbumArtist)
            .isEqualTo("A")
        assertThat(Track(filePath = "/a", title = "t").effectiveAlbumArtist)
            .isEqualTo(Track.UNKNOWN_ARTIST)
    }

    @Test
    fun `effectiveAlbum and effectiveGenre use placeholders when blank`() {
        val bare = Track(filePath = "/a", title = "t")
        assertThat(bare.effectiveAlbum).isEqualTo(Track.UNKNOWN_ALBUM)
        assertThat(bare.effectiveGenre).isEqualTo(Track.UNKNOWN_GENRE)
    }

    @Test
    fun `audio format maps every supported extension`() {
        assertThat(AudioFormat.fromExtension("mp3")).isEqualTo(AudioFormat.MP3)
        assertThat(AudioFormat.fromExtension("MP3")).isEqualTo(AudioFormat.MP3)
        assertThat(AudioFormat.fromExtension("flac")).isEqualTo(AudioFormat.FLAC)
        assertThat(AudioFormat.fromExtension("ogg")).isEqualTo(AudioFormat.OGG)
        assertThat(AudioFormat.fromExtension("opus")).isEqualTo(AudioFormat.OGG)
        assertThat(AudioFormat.fromExtension("m4a")).isEqualTo(AudioFormat.ALAC)
        assertThat(AudioFormat.fromExtension("wav")).isEqualTo(AudioFormat.OTHER)
    }

    @Test
    fun `supported extensions cover the product formats`() {
        assertThat(AudioFormat.SUPPORTED_EXTENSIONS).containsAtLeast("mp3", "flac", "ogg", "m4a")
    }

    @Test
    fun `TagData isEmpty`() {
        assertThat(TagData().isEmpty).isTrue()
        assertThat(TagData(genre = "Rock").isEmpty).isFalse()
        assertThat(TagData(year = 1999).isEmpty).isFalse()
        assertThat(TagData(title = "").isEmpty).isFalse() // "" erases the tag; null leaves it.
    }
}
