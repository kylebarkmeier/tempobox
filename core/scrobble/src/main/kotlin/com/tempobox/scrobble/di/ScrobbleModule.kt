package com.tempobox.scrobble.di

import com.tempobox.scrobble.LastFmScrobbler
import com.tempobox.scrobble.Scrobbler
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/** Hilt bindings for scrobbling (+ the app-wide OkHttp client). */
@Module
@InstallIn(SingletonComponent::class)
abstract class ScrobbleModule {

    @Binds
    abstract fun bindScrobbler(impl: LastFmScrobbler): Scrobbler

    companion object {
        @Provides
        @Singleton
        fun provideOkHttpClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()
    }
}
