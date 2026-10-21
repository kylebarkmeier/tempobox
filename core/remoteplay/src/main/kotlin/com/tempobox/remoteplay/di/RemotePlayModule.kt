package com.tempobox.remoteplay.di

import com.tempobox.remoteplay.DefaultRemotePlayController
import com.tempobox.remoteplay.RemotePlayController
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Hilt bindings for network play. */
@Module
@InstallIn(SingletonComponent::class)
abstract class RemotePlayModule {

    @Binds
    @Singleton
    abstract fun bindRemotePlayController(impl: DefaultRemotePlayController): RemotePlayController
}
