package com.tempobox.tags.di

import com.tempobox.tags.JAudioTaggerIO
import com.tempobox.tags.TagReader
import com.tempobox.tags.TagWriter
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Hilt bindings for tag IO. */
@Module
@InstallIn(SingletonComponent::class)
abstract class TagsModule {

    @Binds
    abstract fun bindTagReader(impl: JAudioTaggerIO): TagReader

    @Binds
    abstract fun bindTagWriter(impl: JAudioTaggerIO): TagWriter
}
