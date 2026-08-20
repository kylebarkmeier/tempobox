package com.tempobox.playback

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.ListenableFuture
import com.tempobox.tags.TagReader
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.guava.future
import java.io.File

/**
 * Media3 [BitmapLoader] that resolves our `tempobox-art:///` artwork URIs by
 * pulling the embedded image out of the audio file's tag. This is what puts
 * album art on the lock screen, the media notification, and Bluetooth AVRCP
 * displays (car head units, headphones with screens).
 */
@UnstableApi
class ArtworkBitmapLoader(
    private val tagReader: TagReader,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
) : BitmapLoader {

    override fun supportsMimeType(mimeType: String): Boolean = mimeType.startsWith("image/")

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
        scope.future(ioDispatcher) {
            requireNotNull(decodeScaled(data)) { "Could not decode artwork bytes" }
        }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> =
        scope.future(ioDispatcher) {
            val path = MediaItems.pathFromArtworkUri(uri)
                ?: uri.path.takeIf { uri.scheme == "file" }
            val bytes = path?.let { tagReader.readEmbeddedArtwork(File(it)) }
            requireNotNull(bytes?.let(::decodeScaled)) { "No embedded artwork for $uri" }
        }

    /** Decodes with down-sampling — lockscreen art doesn't need a 3000px scan. */
    private fun decodeScaled(data: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_DIMENSION_PX ||
            bounds.outHeight / (sample * 2) >= MAX_DIMENSION_PX
        ) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(data, 0, data.size, opts)
    }

    private companion object {
        const val MAX_DIMENSION_PX = 1024
    }
}
