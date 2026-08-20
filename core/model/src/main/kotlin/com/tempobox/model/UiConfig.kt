package com.tempobox.model

import kotlinx.serialization.Serializable

/**
 * Actions that can be bound to a left/right swipe on any library list item
 * (Settings ▸ UI). CREATE_AUTO_PLAYLIST builds a smart playlist from the
 * swiped item using the current view paradigm (e.g. swiping a genre row
 * creates "Genre is X").
 */
@Serializable
enum class SwipeAction {
    NONE,
    ADD_TO_QUEUE,
    ADD_TO_PLAYLIST,
    CREATE_AUTO_PLAYLIST,
    SHUFFLE,
    REMOVE_FROM_LIBRARY, // confirmation dialog first
    EDIT_TAGS,
    DELETE_PERMANENTLY,  // confirmation dialog first
}

/** Actions bindable to the four corners around the Now Playing album art. */
@Serializable
enum class CornerAction {
    NONE,
    SHUFFLE_TOGGLE,
    REPEAT_TOGGLE,
    OPEN_QUEUE,
    ADD_TO_PLAYLIST,
    RATE_TRACK,
    EDIT_TAGS,
    SET_AS_WALLPAPER, // sets current album art as device wallpaper
}

/** The four corners of the Now Playing artwork. */
enum class Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

/** Operations a triple-tap of a volume key can trigger while the screen is off. */
@Serializable
enum class VolumeTapAction { NONE, NEXT_TRACK, PREVIOUS_TRACK, PLAY_PAUSE, STOP }

/** Physical/Bluetooth media buttons the user can remap. */
@Serializable
enum class MediaButton { PLAY_PAUSE, NEXT, PREVIOUS, STOP }

/** What a remapped media button should do. */
@Serializable
enum class MediaButtonAction { DEFAULT, PLAY_PAUSE, NEXT_TRACK, PREVIOUS_TRACK, STOP, NONE }

/** Top-level library tabs. RECENTLY_ADDED nests its own sub-tabs. */
@Serializable
enum class LibraryTab { ALBUM_ARTISTS, ALBUMS, GENRES, TRACKS, PLAYLISTS, RECENTLY_ADDED }

/** Card (artwork grid) vs. plain list presentation for artist/album views. */
@Serializable
enum class ViewLayout { CARD, LIST }

/**
 * An entry in the side navigation drawer. The four built-ins are fixed;
 * users can add pinned library views (e.g. "Genres") from Settings ▸ UI.
 */
@Serializable
sealed interface DrawerItem {
    @Serializable data object Library : DrawerItem
    @Serializable data object NowPlaying : DrawerItem
    @Serializable data object Queue : DrawerItem
    @Serializable data object Settings : DrawerItem

    /** User-configured shortcut straight to one library tab. */
    @Serializable data class LibraryView(val tab: LibraryTab, val label: String) : DrawerItem
}

/** User-customizable theme colors (stored as ARGB ints) and dark-mode choice. */
@Serializable
data class ThemeConfig(
    val useDynamicColor: Boolean = false,
    val darkMode: DarkMode = DarkMode.SYSTEM,
    val primaryArgb: Long = 0xFF6750A4, // Material3 baseline primary
    val secondaryArgb: Long = 0xFF625B71,
    val tertiaryArgb: Long = 0xFF7D5260,
) {
    @Serializable
    enum class DarkMode { SYSTEM, LIGHT, DARK }
}
