package com.tempobox.settings

import com.tempobox.model.Corner
import com.tempobox.model.CornerAction
import com.tempobox.model.DrawerItem
import com.tempobox.model.MediaButton
import com.tempobox.model.MediaButtonAction
import com.tempobox.model.SwipeAction
import com.tempobox.model.ThemeConfig
import com.tempobox.model.TrackInfoField
import com.tempobox.model.ViewLayout
import com.tempobox.model.VolumeTapAction
import kotlinx.serialization.Serializable

/**
 * Immutable snapshot of every user preference, grouped the same way the
 * Settings screen is. Defaults here ARE the product defaults — change them in
 * one place only.
 *
 * Persisted via Preferences DataStore in [DataStoreSettingsRepository];
 * complex values are stored as JSON.
 */
data class AppSettings(
    val library: LibrarySettings = LibrarySettings(),
    val ui: UiSettings = UiSettings(),
    val nowPlaying: NowPlayingSettings = NowPlayingSettings(),
    val queue: QueueSettings = QueueSettings(),
    val bluetooth: BluetoothSettings = BluetoothSettings(),
    val shuffle: ShuffleSettings = ShuffleSettings(),
    val lastFm: LastFmSettings = LastFmSettings(),
    val artwork: ArtworkSettings = ArtworkSettings(),
    val theme: ThemeConfig = ThemeConfig(),
)

@Serializable
data class ArtworkSettings(
    /**
     * Personal access token for discogs.com (Settings ▸ UI ▸ Artist images).
     * Empty = skip Discogs and always use the album-art collage fallback.
     */
    val discogsToken: String = "",
    /** Card view: prefer fetched artist photos over the album-art collage. */
    val preferArtistImages: Boolean = true,
)

@Serializable
data class LibrarySettings(
    /** Absolute folder paths scanned for audio files. */
    val locations: List<String> = emptyList(),
    /** Rescan on app start AND watch locations for changes while running. */
    val autoRescanAndWatch: Boolean = true, // product default: ON
    /** Window (days) for the "Recently Added" views. */
    val recentlyAddedDays: Int = 14,
)

@Serializable
data class UiSettings(
    /** Swipe actions on library list items. */
    val swipeLeft: SwipeAction = SwipeAction.ADD_TO_QUEUE,
    val swipeRight: SwipeAction = SwipeAction.ADD_TO_PLAYLIST,
    /** Drawer contents; users may append LibraryView shortcuts from Settings ▸ UI. */
    val drawerItems: List<DrawerItem> = DEFAULT_DRAWER_ITEMS,
    /** Card (artwork) vs. list presentation for the artist/album tabs. */
    val artistLayout: ViewLayout = ViewLayout.CARD,
    val albumLayout: ViewLayout = ViewLayout.CARD,
) {
    companion object {
        val DEFAULT_DRAWER_ITEMS: List<DrawerItem> = listOf(
            DrawerItem.Library,
            DrawerItem.NowPlaying,
            DrawerItem.Queue,
            DrawerItem.Settings,
        )
    }
}

@Serializable
data class NowPlayingSettings(
    /** Action bound to each corner of the album art. */
    val cornerActions: Map<Corner, CornerAction> = mapOf(
        Corner.TOP_LEFT to CornerAction.NONE,
        Corner.TOP_RIGHT to CornerAction.OPEN_QUEUE,
        Corner.BOTTOM_LEFT to CornerAction.SHUFFLE_TOGGLE,
        Corner.BOTTOM_RIGHT to CornerAction.REPEAT_TOGGLE,
    ),
    /** Metadata lines shown under the title, in order. */
    val trackInfoFields: List<TrackInfoField> = listOf(
        TrackInfoField.ARTIST,
        TrackInfoField.ALBUM,
        TrackInfoField.YEAR,
    ),
)

@Serializable
data class QueueSettings(
    /** Restore the queue (tracks, index, position) after process death/restart. */
    val persistQueue: Boolean = true,
    /** Ask before "Clear queue" wipes everything. */
    val confirmClearQueue: Boolean = true,
    /** Allow the same track to be enqueued more than once. */
    val allowDuplicates: Boolean = true,
)

@Serializable
data class BluetoothSettings(
    /** Start playback automatically when a Bluetooth audio device connects. */
    val startOnConnect: Boolean = false,
    /** Triple-tap volume-up action (works with screen off during playback). */
    val volumeUpTripleTap: VolumeTapAction = VolumeTapAction.NONE,
    /** Triple-tap volume-down action (works with screen off during playback). */
    val volumeDownTripleTap: VolumeTapAction = VolumeTapAction.NONE,
    /** Remaps AVRCP/headset media buttons. DEFAULT = system behavior. */
    val buttonRemap: Map<MediaButton, MediaButtonAction> = mapOf(
        MediaButton.PLAY_PAUSE to MediaButtonAction.DEFAULT,
        MediaButton.NEXT to MediaButtonAction.DEFAULT,
        MediaButton.PREVIOUS to MediaButtonAction.DEFAULT,
        MediaButton.STOP to MediaButtonAction.DEFAULT,
    ),
)

@Serializable
data class ShuffleSettings(
    /**
     * Anti-repeat shuffle (product default ON): orders the pool so the same
     * track/album/artist repeats as far apart as possible.
     */
    val antiRepeat: Boolean = true,
    /** Bias shuffle toward higher-rated tracks (5★ strongest). */
    val ratingBias: Boolean = false,
)

@Serializable
data class LastFmSettings(
    val scrobbleEnabled: Boolean = false,
    /** User-provided API credentials (create at last.fm/api/account/create). */
    val apiKey: String = "",
    val apiSecret: String = "",
    val username: String = "",
    /** Obtained via mobile auth; empty = not authenticated. */
    val sessionKey: String = "",
    /** Also send Now Playing updates (not just scrobbles). */
    val updateNowPlaying: Boolean = true,
)
