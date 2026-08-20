package com.tempobox.database.di

import android.content.Context
import androidx.room.Room
import com.tempobox.database.TempoBoxDatabase
import com.tempobox.database.dao.PlaylistDao
import com.tempobox.database.dao.TrackDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Hilt bindings for the Room database and its DAOs. */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): TempoBoxDatabase =
        Room.databaseBuilder(context, TempoBoxDatabase::class.java, TempoBoxDatabase.NAME)
            // v1 is the first shipped schema; from v2 on, real migrations are required.
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideTrackDao(db: TempoBoxDatabase): TrackDao = db.trackDao()

    @Provides
    fun providePlaylistDao(db: TempoBoxDatabase): PlaylistDao = db.playlistDao()
}
