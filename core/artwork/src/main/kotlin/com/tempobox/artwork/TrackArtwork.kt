package com.tempobox.artwork

import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import com.tempobox.tags.TagReader
import okio.Buffer
import java.io.File
import javax.inject.Inject

/**
 * Coil model for "the embedded artwork of the audio file at [path]".
 * Use anywhere an AsyncImage needs album art:
 * `AsyncImage(model = TrackArtwork(track.filePath), ...)`.
 */
data class TrackArtwork(val path: String)

/**
 * Coil [Fetcher] that pulls artwork bytes out of the audio file's tag via
 * [TagReader]. Registered on the app's ImageLoader in `TempoBoxApplication`.
 * Coil's memory/disk caches key on [TrackArtwork.path] + file mtime, so edits
 * to a file's art invalidate stale cache entries.
 */
class TrackArtworkFetcher(
    private val model: TrackArtwork,
    private val options: Options,
    private val tagReader: TagReader,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val bytes = tagReader.readEmbeddedArtwork(File(model.path)) ?: return null
        return SourceResult(
            source = ImageSource(Buffer().write(bytes), options.context),
            mimeType = null, // let Coil sniff (JPEG/PNG/WebP all appear in tags)
            dataSource = DataSource.DISK,
        )
    }

    class Factory @Inject constructor(
        private val tagReader: TagReader,
    ) : Fetcher.Factory<TrackArtwork> {
        override fun create(data: TrackArtwork, options: Options, imageLoader: ImageLoader): Fetcher =
            TrackArtworkFetcher(data, options, tagReader)
    }
}

/**
 * Coil cache keyer: include the file's mtime so re-tagged artwork isn't served
 * stale from cache.
 */
class TrackArtworkKeyer : coil.key.Keyer<TrackArtwork> {
    override fun key(data: TrackArtwork, options: Options): String =
        "trackart:${data.path}:${File(data.path).lastModified()}"
}
