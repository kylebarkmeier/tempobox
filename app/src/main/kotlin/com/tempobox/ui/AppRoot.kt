package com.tempobox.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.tempobox.model.DrawerItem
import com.tempobox.model.LibraryTab
import com.tempobox.ui.library.LibraryActionsViewModel
import com.tempobox.ui.navigation.Routes
import com.tempobox.ui.navigation.TempoBoxNavHost
import com.tempobox.ui.nowplaying.NowPlayingSheet
import com.tempobox.ui.nowplaying.SheetStage
import com.tempobox.ui.nowplaying.rememberNowPlayingSheetState
import com.tempobox.ui.theme.TempoBoxTheme
import kotlinx.coroutines.launch

/** Snackbar host shared by every screen (action feedback, errors). */
val LocalSnackbar = compositionLocalOf { SnackbarHostState() }

/**
 * Executes "Go to artist/album" requests from the shared action layer
 * ([com.tempobox.ui.library.LibraryActionsViewModel.Navigation]). Provided by
 * [AppRoot] (where the NavController lives) and collected via ActionDialogHost,
 * so every screen hosting the standard menus navigates identically.
 */
val LocalLibraryNavigator =
    compositionLocalOf<(LibraryActionsViewModel.Navigation) -> Unit> { {} }

/**
 * App shell: theme ← settings, side navigation drawer (Library, Now Playing,
 * Queue, Settings + user-added library shortcuts), NavHost, and the Now
 * Playing sheet layered over everything: collapsed it is the mini-player pill
 * docked at the bottom, dragged or tapped up it becomes the full Now Playing
 * view ([com.tempobox.ui.nowplaying.NowPlayingSheet]).
 */
@Composable
fun AppRoot(navController: NavHostController = rememberNavController()) {
    val viewModel: MainViewModel = hiltViewModel()
    val settings by viewModel.settings.collectAsState()
    val nowPlaying by viewModel.nowPlaying.collectAsState()

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val sheetState = rememberNowPlayingSheetState()

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

    // "Go to artist/album" from the shared action layer's menus, resolved to
    // the same detail routes the library screens use. The sheet collapses
    // first so the destination isn't hidden under an expanded Now Playing.
    val libraryNavigator: (LibraryActionsViewModel.Navigation) -> Unit =
        remember(navController, sheetState) {
            { destination ->
                sheetState.collapse()
                when (destination) {
                    is LibraryActionsViewModel.Navigation.ToArtist ->
                        navController.navigate(Routes.artist(destination.name, destination.byAlbumArtist))
                    is LibraryActionsViewModel.Navigation.ToAlbum ->
                        navController.navigate(Routes.album(destination.albumArtist, destination.album))
                }
            }
        }

    TempoBoxTheme(config = settings.theme) {
        CompositionLocalProvider(
            LocalSnackbar provides snackbarHostState,
            LocalLibraryNavigator provides libraryNavigator,
        ) {
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
                                    DrawerItem.NowPlaying -> sheetState.stage == SheetStage.EXPANDED
                                    DrawerItem.Queue -> currentRoute == Routes.QUEUE
                                    DrawerItem.Settings -> currentRoute == Routes.SETTINGS
                                }
                                NavigationDrawerItem(
                                    label = { Text(label) },
                                    icon = { Icon(icon, contentDescription = null) },
                                    selected = selected,
                                    onClick = {
                                        // Now Playing is the sheet, not a route.
                                        if (route == null) {
                                            scope.launch { drawerState.close() }
                                            sheetState.expand()
                                        } else {
                                            navigate(route)
                                        }
                                    },
                                    modifier = Modifier.padding(horizontal = 12.dp),
                                )
                            }
                        }
                    }
                },
            ) {
                val pillHeight = with(LocalDensity.current) { sheetState.pillHeightPx.toDp() }
                Box(Modifier.fillMaxSize()) {
                    Scaffold(
                        // No top inset here: every screen has its own TopAppBar,
                        // which already applies the status-bar inset — padding it
                        // twice leaves a blank band above the header.
                        contentWindowInsets = WindowInsets.systemBars
                            .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
                        bottomBar = {
                            // Reserve the mini-player pill's space; the pill
                            // itself is the collapsed Now Playing sheet drawn
                            // over the scaffold.
                            if (nowPlaying.track != null) {
                                Spacer(Modifier.height(pillHeight))
                            }
                        },
                    ) { padding ->
                        TempoBoxNavHost(
                            navController = navController,
                            openDrawer = { scope.launch { drawerState.open() } },
                            modifier = Modifier.padding(padding),
                        )
                    }

                    NowPlayingSheet(
                        state = sheetState,
                        nowPlaying = nowPlaying,
                        player = viewModel.player,
                        onOpenArtist = { name ->
                            sheetState.collapse()
                            navController.navigate(Routes.artist(name, byAlbumArtist = false))
                        },
                        onOpenAlbum = { artist, album ->
                            sheetState.collapse()
                            navController.navigate(Routes.album(artist, album))
                        },
                    )

                    // Above the sheet so feedback stays visible over expanded
                    // Now Playing, and clear of the pill when collapsed.
                    SnackbarHost(
                        snackbarHostState,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .then(
                                if (nowPlaying.track != null) {
                                    Modifier.padding(bottom = pillHeight)
                                } else {
                                    Modifier.navigationBarsPadding()
                                },
                            ),
                    )
                }
            }
        }
    }
}

private fun drawerEntry(item: DrawerItem): Triple<String, ImageVector, String?> = when (item) {
    DrawerItem.Library -> Triple("Library", Icons.Filled.LibraryMusic, Routes.library())
    // Null route: the drawer item expands the Now Playing sheet instead.
    DrawerItem.NowPlaying -> Triple("Now Playing", Icons.Filled.PlayCircle, null)
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
