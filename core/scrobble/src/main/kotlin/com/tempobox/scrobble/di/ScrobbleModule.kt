package com.tempobox.scrobble.di

import com.tempobox.scrobble.BroadcastScrobbler
import com.tempobox.scrobble.Scrobbler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Hilt bindings for scrobbling. */
@Module
@InstallIn(SingletonComponent::class)
abstract class ScrobbleModule {

    @Binds
    abstract fun bindScrobbler(impl: BroadcastScrobbler): Scrobbler
}
