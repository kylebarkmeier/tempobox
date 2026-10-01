package com.tempobox.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.tempobox.model.DrawerItem
import com.tempobox.model.LibraryTab
import com.tempobox.ui.navigation.Routes
import com.tempobox.ui.navigation.TempoBoxNavHost
import com.tempobox.ui.nowplaying.MiniPlayer
import com.tempobox.ui.theme.TempoBoxTheme
import kotlinx.coroutines.launch

/** Snackbar host shared by every screen (action feedback, errors). */
val LocalSnackbar = compositionLocalOf { SnackbarHostState() }

/**
 * App shell: theme ← settings, side navigation drawer (Library, Now Playing,
 * Queue, Settings + user-added library shortcuts), NavHost, and the
 * mini-player bar docked at the bottom whenever something is loaded.
 */
@Composable
fun AppRoot(navController: NavHostController = rememberNavController()) {
    val viewModel: MainViewModel = hiltViewModel()
    val settings by viewModel.settings.collectAsState()
    val nowPlaying by viewModel.nowPlaying.collectAsState()

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    fun navigate(route: String) {
        scope.launch { drawerState.close() }
        navController.navigate(route) {
            launchSingleTop = true
            popUpTo(Routes.LIBRARY) { saveState = true }
            restoreState = true
        }
    }

    TempoBoxTheme(config = settings.theme) {
        CompositionLocalProvider(LocalSnackbar provides snackbarHostState) {
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    ModalDrawerSheet {
                        Text(
                            "TempoBox",
                            style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(16.dp),
                        )
                        Column {
                            settings.ui.drawerItems.forEach { item ->
                                val (label, icon, route) = drawerEntry(item)
                                // currentRoute is the destination *pattern*, so
                                // library shortcuts match the library pattern.
                                val selected = when (item) {
                                    DrawerItem.Library, is DrawerItem.LibraryView ->
                                        currentRoute == Routes.LIBRARY
                                    DrawerItem.NowPlaying -> currentRoute == Routes.NOW_PLAYING
                                    DrawerItem.Queue -> currentRoute == Routes.QUEUE
                                    DrawerItem.Settings -> currentRoute == Routes.SETTINGS
                                }
                                NavigationDrawerItem(
                                    label = { Text(label) },
                                    icon = { Icon(icon, contentDescription = null) },
                                    selected = selected,
                                    onClick = { navigate(route) },
                                    modifier = Modifier.padding(horizontal = 12.dp),
                                )
                            }
                        }
                    }
                },
            ) {
                Scaffold(
                    // No top inset here: every screen has its own TopAppBar,
                    // which already applies the status-bar inset — padding it
                    // twice leaves a blank band above the header.
                    contentWindowInsets = WindowInsets.systemBars
                        .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    bottomBar = {
                        // Mini player everywhere except the full Now Playing view.
                        if (nowPlaying.track != null && currentRoute != Routes.NOW_PLAYING) {
                            MiniPlayer(
                                state = nowPlaying,
                                player = viewModel.player,
                                onOpen = { navigate(Routes.NOW_PLAYING) },
                            )
                        }
                    },
                ) { padding ->
                    TempoBoxNavHost(
                        navController = navController,
                        openDrawer = { scope.launch { drawerState.open() } },
                        modifier = Modifier.padding(padding),
                    )
                }
            }
        }
    }
}

private fun drawerEntry(item: DrawerItem): Triple<String, ImageVector, String> = when (item) {
    DrawerItem.Library -> Triple("Library", Icons.Filled.LibraryMusic, Routes.library())
    DrawerItem.NowPlaying -> Triple("Now Playing", Icons.Filled.PlayCircle, Routes.NOW_PLAYING)
    DrawerItem.Queue -> Triple("Queue", Icons.AutoMirrored.Filled.QueueMusic, Routes.QUEUE)
    DrawerItem.Settings -> Triple("Settings", Icons.Filled.Settings, Routes.SETTINGS)
    is DrawerItem.LibraryView -> Triple(
        item.label,
        tabIcon(item.tab),
        Routes.library(item.tab),
    )
}

fun tabIcon(tab: LibraryTab): ImageVector = when (tab) {
    LibraryTab.ALBUM_ARTISTS -> Icons.Filled.MusicNote
    LibraryTab.ARTISTS -> Icons.Filled.MusicNote
    LibraryTab.ALBUMS -> Icons.Filled.LibraryMusic
    LibraryTab.GENRES -> Icons.Filled.MusicNote
    LibraryTab.TRACKS -> Icons.Filled.MusicNote
    LibraryTab.PLAYLISTS -> Icons.AutoMirrored.Filled.QueueMusic
}
