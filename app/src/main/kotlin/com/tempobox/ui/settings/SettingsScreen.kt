package com.tempobox.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel

/** Settings home: one row per section. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    openDrawer: () -> Unit,
    onOpenSection: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = {
                IconButton(onClick = openDrawer) {
                    Icon(Icons.Filled.Menu, contentDescription = "Open navigation")
                }
            },
        )
        Column(Modifier.verticalScroll(rememberScrollState())) {
            SectionRow("library", "Library", "Locations, scanning, reset", Icons.Filled.LibraryMusic, onOpenSection)
            SectionRow("ui", "UI", "Swipe gestures, drawer items, artist images", Icons.Filled.TouchApp, onOpenSection)
            SectionRow("nowplaying", "Now Playing", "Corner buttons, track info", Icons.Filled.PlayCircle, onOpenSection)
            SectionRow("queue", "Queue", "Persistence, confirmations", Icons.AutoMirrored.Filled.QueueMusic, onOpenSection)
            SectionRow("bluetooth", "Bluetooth & buttons", "Autoplay, volume gestures, remapping", Icons.Filled.Bluetooth, onOpenSection)
            SectionRow("shuffle", "Shuffle", "Anti-repeat, rating bias", Icons.Filled.Shuffle, onOpenSection)
            SectionRow("lastfm", "Last.fm scrobbling", "Account, API keys", Icons.Filled.CloudSync, onOpenSection)
            SectionRow("theme", "Theme", "Colors, dark mode", Icons.Filled.Palette, onOpenSection)
        }
    }
}

@Composable
private fun SectionRow(
    key: String,
    title: String,
    subtitle: String,
    icon: ImageVector,
    onOpenSection: (String) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, contentDescription = null) },
        modifier = Modifier.clickable { onOpenSection(key) },
    )
}

/** Per-section screen; dispatches on the route's `section` argument. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSectionScreen(section: String, onBack: () -> Unit) {
    val viewModel: SettingsViewModel = hiltViewModel()

    val title = when (section) {
        "library" -> "Library"
        "ui" -> "UI"
        "nowplaying" -> "Now Playing"
        "queue" -> "Queue"
        "bluetooth" -> "Bluetooth & buttons"
        "shuffle" -> "Shuffle"
        "lastfm" -> "Last.fm"
        "theme" -> "Theme"
        else -> "Settings"
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(title) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )
        Column(Modifier.verticalScroll(rememberScrollState())) {
            when (section) {
                "library" -> LibrarySection(viewModel)
                "ui" -> UiSection(viewModel)
                "nowplaying" -> NowPlayingSection(viewModel)
                "queue" -> QueueSection(viewModel)
                "bluetooth" -> BluetoothSection(viewModel)
                "shuffle" -> ShuffleSection(viewModel)
                "lastfm" -> LastFmSection(viewModel)
                "theme" -> ThemeSection(viewModel)
            }
        }
    }
}
