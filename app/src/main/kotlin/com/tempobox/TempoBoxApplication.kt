package com.tempobox

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.tempobox.artwork.TrackArtworkFetcher
import com.tempobox.artwork.TrackArtworkKeyer
import com.tempobox.common.ApplicationScope
import com.tempobox.library.LibraryInitializer
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import javax.inject.Inject

/**
 * App entry point. Wires the pieces that must exist for the whole process:
 *  - library lifecycle (startup rescan, folder watching, smart playlist sync)
 *  - the Coil ImageLoader that can read artwork embedded in audio tags
 */
@HiltAndroidApp
class TempoBoxApplication : Application(), ImageLoaderFactory {

    @Inject lateinit var libraryInitializer: LibraryInitializer
    @Inject lateinit var trackArtworkFetcherFactory: TrackArtworkFetcher.Factory
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        libraryInitializer.onAppStart()
    }

    /** App-wide Coil loader: understands `TrackArtwork(path)` models. */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components {
                add(trackArtworkFetcherFactory)
                add(TrackArtworkKeyer())
            }
            .crossfade(true)
            .build()
}
