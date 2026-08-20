package com.tempobox.settings.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.tempobox.settings.DataStoreSettingsRepository
import com.tempobox.settings.SettingsRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Process-wide DataStore instance. A property delegate (not a Hilt @Singleton
 * factory) because @Singleton is only unique per Hilt component — instrumented
 * tests build a fresh component per test method, and a second DataStore on the
 * same file crashes ("multiple DataStores active"). Same pattern as
 * PlaybackStateStore.playbackDataStore.
 */
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "tempobox_settings",
)

/** Hilt bindings for the settings DataStore and repository. */
@Module
@InstallIn(SingletonComponent::class)
abstract class SettingsModule {

    @Binds
    abstract fun bindSettingsRepository(impl: DataStoreSettingsRepository): SettingsRepository

    companion object {
        @Provides
        @Singleton
        fun provideSettingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
            context.settingsDataStore
    }
}
