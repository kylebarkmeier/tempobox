package com.tempobox.ui.navigation

import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.tempobox.model.LibraryTab
import com.tempobox.ui.library.AlbumDetailScreen
import com.tempobox.ui.library.ArtistDetailScreen
import com.tempobox.ui.library.GenreDetailScreen
import com.tempobox.ui.library.LibraryScreen
import com.tempobox.ui.library.PlaylistDetailScreen
import com.tempobox.ui.queue.QueueScreen
import com.tempobox.ui.settings.SettingsScreen
import com.tempobox.ui.settings.SettingsSectionScreen

/** The navigation graph. Route constants/builders live in [Routes]. */
@Composable
fun TempoBoxNavHost(
    navController: NavHostController,
    openDrawer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = Routes.LIBRARY,
        modifier = modifier,
    ) {
        composable(
            route = Routes.LIBRARY,
            arguments = listOf(
                navArgument("tab") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            val tab = entry.arguments?.getString("tab")
                ?.let { runCatching { LibraryTab.valueOf(it) }.getOrNull() }
            LibraryScreen(
                initialTab = tab,
                openDrawer = openDrawer,
                onOpenArtist = { name, byAlbumArtist ->
                    navController.navigate(Routes.artist(name, byAlbumArtist))
                },
                onOpenAlbum = { artist, album -> navController.navigate(Routes.album(artist, album)) },
                onOpenGenre = { navController.navigate(Routes.genre(it)) },
                onOpenPlaylist = { navController.navigate(Routes.playlist(it)) },
            )
        }

        // Now Playing is not a destination: it is the draggable sheet hosted
        // by AppRoot over this NavHost.

        composable(Routes.QUEUE) {
            QueueScreen(openDrawer = openDrawer, onBack = { navController.popBackStack() })
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                openDrawer = openDrawer,
                onOpenSection = { navController.navigate(Routes.settingsSection(it)) },
            )
        }

        composable(
            route = Routes.SETTINGS_SECTION,
            arguments = listOf(navArgument("section") { type = NavType.StringType }),
        ) { entry ->
            SettingsSectionScreen(
                section = entry.arguments?.getString("section").orEmpty(),
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = Routes.ARTIST,
            arguments = listOf(
                navArgument("name") { type = NavType.StringType },
                navArgument("by") {
                    type = NavType.StringType
                    defaultValue = "album"
                },
            ),
        ) { entry ->
            ArtistDetailScreen(
                name = entry.arguments?.getString("name").orEmpty(),
                onBack = { navController.popBackStack() },
                onOpenAlbum = { artist, album -> navController.navigate(Routes.album(artist, album)) },
            )
        }

        composable(
            route = Routes.ALBUM,
            arguments = listOf(
                navArgument("artist") { type = NavType.StringType },
                navArgument("album") { type = NavType.StringType },
            ),
        ) { entry ->
            AlbumDetailScreen(
                albumArtist = entry.arguments?.getString("artist").orEmpty(),
                album = entry.arguments?.getString("album").orEmpty(),
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = Routes.GENRE,
            arguments = listOf(navArgument("name") { type = NavType.StringType }),
        ) { entry ->
            GenreDetailScreen(
                name = entry.arguments?.getString("name").orEmpty(),
                onBack = { navController.popBackStack() },
                onOpenArtist = { navController.navigate(Routes.artist(it)) },
                onOpenAlbum = { artist, album -> navController.navigate(Routes.album(artist, album)) },
            )
        }

        composable(
            route = Routes.PLAYLIST,
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) { entry ->
            PlaylistDetailScreen(
                playlistId = entry.arguments?.getLong("id") ?: 0L,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
