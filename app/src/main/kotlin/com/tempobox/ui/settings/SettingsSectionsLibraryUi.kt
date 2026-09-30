package com.tempobox.ui.settings

import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.tempobox.library.ScanState
import com.tempobox.model.DrawerItem
import com.tempobox.model.LibraryTab
import com.tempobox.model.SwipeAction
import com.tempobox.ui.components.ConfirmDialog
import com.tempobox.ui.components.TextInputDialog
import com.tempobox.ui.components.swipeLabel

// ---------------------------------------------------------------- Library

/**
 * Settings ▸ Library: locations, rescan, auto-rescan/watch toggle (default
 * ON), All-files access, and double-confirmed reset.
 */
@Composable
fun LibrarySection(viewModel: SettingsViewModel) {
    val settings by viewModel.settings.collectAsState()
    val scanState by viewModel.scanState.collectAsState()
    val context = LocalContext.current

    var addingManually by rememberSaveable { mutableStateOf(false) }
    var resetStep by rememberSaveable { mutableStateOf(0) } // 0 = idle, 1 & 2 = confirmations

    // System folder picker; the tree URI is converted to a filesystem path.
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        uri?.let { treeUriToPath(it) }?.let { viewModel.addLibraryLocation(it) }
    }

    SettingsSection("Locations") {
        settings.library.locations.forEach { path ->
            ListItem(
                headlineContent = { Text(path) },
                trailingContent = {
                    IconButton(onClick = { viewModel.removeLibraryLocation(path) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Remove $path")
                    }
                },
            )
        }
        ActionRow("Add folder…", "Pick with the system folder browser") {
            folderPicker.launch(null)
        }
        ActionRow("Add path manually…", "e.g. /storage/emulated/0/Music") {
            addingManually = true
        }
    }

    SettingsDivider()
    SettingsSection("Scanning") {
        ActionRow(
            "Rescan library",
            subtitle = when (val s = scanState) {
                is ScanState.Scanning -> "Scanning… ${s.scanned}/${if (s.total > 0) s.total else "?"}"
                is ScanState.Done -> "Last scan: +${s.added} added, ${s.updated} updated, −${s.removed} removed"
                ScanState.Idle -> "Scan all locations now"
            },
        ) { viewModel.rescanLibrary() }
        SwitchRow(
            title = "Rescan on start & watch locations",
            subtitle = "Automatically pick up added, changed and deleted files",
            checked = settings.library.autoRescanAndWatch,
            onToggle = { on -> viewModel.updateLibrary { it.copy(autoRescanAndWatch = on) } },
        )
    }

    SettingsDivider()
    SettingsSection("File access") {
        val granted = viewModel.hasAllFilesAccess()
        ActionRow(
            if (granted) "All files access: granted" else "Grant All files access",
            subtitle = "Required for editing ID3 tags and deleting files in your library folders",
        ) {
            if (!granted) context.startActivity(viewModel.allFilesAccessIntent())
        }
    }

    SettingsDivider()
    SettingsSection("Danger zone") {
        ActionRow(
            "Reset library",
            subtitle = "Remove every track from the database (files are not touched)",
            destructive = true,
        ) { resetStep = 1 }
    }

    if (addingManually) {
        TextInputDialog(
            title = "Add library path",
            placeholder = "/storage/emulated/0/Music",
            confirmLabel = "Add",
            onConfirm = {
                viewModel.addLibraryLocation(it)
                addingManually = false
            },
            onDismiss = { addingManually = false },
        )
    }

    // Two confirmation dialogs, per product spec.
    if (resetStep == 1) {
        ConfirmDialog(
            title = "Reset library?",
            text = "This removes every track, rating and play count from TempoBox's database. " +
                "Audio files on the device are NOT deleted.",
            confirmLabel = "Continue",
            destructive = true,
            onConfirm = { resetStep = 2 },
            onDismiss = { resetStep = 0 },
        )
    }
    if (resetStep == 2) {
        ConfirmDialog(
            title = "Are you absolutely sure?",
            text = "Ratings and play counts cannot be recovered after a reset.",
            confirmLabel = "Reset library",
            destructive = true,
            onConfirm = {
                viewModel.resetLibrary()
                resetStep = 0
            },
            onDismiss = { resetStep = 0 },
        )
    }
}

/**
 * Best-effort conversion of an OpenDocumentTree URI to a filesystem path:
 * `primary:Music` → /storage/emulated/0/Music, `1D04-330A:Music` →
 * /storage/1D04-330A/Music. Falls back to null for exotic providers (the
 * manual-path dialog covers those).
 */
fun treeUriToPath(uri: Uri): String? {
    val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
    val parts = docId.split(":", limit = 2)
    val volume = parts.getOrNull(0) ?: return null
    val relative = parts.getOrNull(1).orEmpty()
    val root = if (volume == "primary") {
        Environment.getExternalStorageDirectory().absolutePath
    } else {
        "/storage/$volume"
    }
    return if (relative.isBlank()) root else "$root/$relative"
}

// ---------------------------------------------------------------- UI

/**
 * Settings ▸ UI: swipe gesture bindings, drawer shortcuts, artist images.
 */
@Composable
fun UiSection(viewModel: SettingsViewModel) {
    val settings by viewModel.settings.collectAsState()
    var addingDrawerItem by rememberSaveable { mutableStateOf(false) }

    SettingsSection("Swipe gestures") {
        val actionsList = SwipeAction.entries
        DropdownRow(
            title = "Swipe left",
            currentLabel = swipeLabel(settings.ui.swipeLeft),
            options = actionsList.map { swipeLabel(it) },
        ) { picked -> viewModel.updateUi { it.copy(swipeLeft = actionsList[picked]) } }
        DropdownRow(
            title = "Swipe right",
            currentLabel = swipeLabel(settings.ui.swipeRight),
            options = actionsList.map { swipeLabel(it) },
        ) { picked -> viewModel.updateUi { it.copy(swipeRight = actionsList[picked]) } }
    }

    SettingsDivider()
    SettingsSection("Navigation drawer") {
        settings.ui.drawerItems.forEach { item ->
            when (item) {
                is DrawerItem.LibraryView -> ListItem(
                    headlineContent = { Text(item.label) },
                    supportingContent = { Text("Library shortcut") },
                    trailingContent = {
                        IconButton(onClick = {
                            viewModel.updateUi { ui ->
                                ui.copy(drawerItems = ui.drawerItems - item)
                            }
                        }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Remove ${item.label}")
                        }
                    },
                )
                else -> ListItem(
                    headlineContent = { Text(builtInLabel(item)) },
                    supportingContent = { Text("Built-in") },
                )
            }
        }
        ActionRow("Add library shortcut…", "Pin a library view to the drawer") {
            addingDrawerItem = true
        }
    }

    SettingsDivider()
    SettingsSection("Artist images") {
        SwitchRow(
            title = "Prefer artist photos",
            subtitle = "Fetch artist images from discogs.com for the card view; " +
                "falls back to an album-art collage",
            checked = settings.artwork.preferArtistImages,
            onToggle = { on -> viewModel.updateArtwork { it.copy(preferArtistImages = on) } },
        )
        var editingToken by rememberSaveable { mutableStateOf(false) }
        ActionRow(
            "Discogs access token",
            subtitle = if (settings.artwork.discogsToken.isBlank()) {
                "Not set — create one at discogs.com/settings/developers"
            } else {
                "Configured"
            },
        ) { editingToken = true }
        ActionRow("Clear artist image cache", "Re-fetch images on next browse") {
            viewModel.clearArtistImageCache()
        }
        if (editingToken) {
            TextInputDialog(
                title = "Discogs token",
                initialValue = settings.artwork.discogsToken,
                confirmLabel = "Save",
                onConfirm = { token ->
                    viewModel.updateArtwork { it.copy(discogsToken = token.trim()) }
                    editingToken = false
                },
                onDismiss = { editingToken = false },
            )
        }
    }

    if (addingDrawerItem) {
        AddDrawerItemDialog(
            onAdd = { tab, label ->
                viewModel.updateUi { ui ->
                    ui.copy(drawerItems = ui.drawerItems + DrawerItem.LibraryView(tab, label))
                }
                addingDrawerItem = false
            },
            onDismiss = { addingDrawerItem = false },
        )
    }
}

private fun builtInLabel(item: DrawerItem): String = when (item) {
    DrawerItem.Library -> "Library"
    DrawerItem.NowPlaying -> "Now Playing"
    DrawerItem.Queue -> "Queue"
    DrawerItem.Settings -> "Settings"
    is DrawerItem.LibraryView -> item.label
}

/** Pick a library tab + label for a new drawer shortcut. */
@Composable
private fun AddDrawerItemDialog(
    onAdd: (LibraryTab, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(LibraryTab.GENRES) }
    var step by rememberSaveable { mutableStateOf(0) }

    if (step == 0) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Which view?") },
            text = {
                Column {
                    LibraryTab.entries.forEach { candidate ->
                        ListItem(
                            headlineContent = { Text(tabName(candidate)) },
                            modifier = Modifier.clickable {
                                tab = candidate
                                step = 1
                            },
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") }
            },
        )
    } else {
        TextInputDialog(
            title = "Shortcut label",
            initialValue = tabName(tab),
            confirmLabel = "Add",
            onConfirm = { onAdd(tab, it) },
            onDismiss = onDismiss,
        )
    }
}

fun tabName(tab: LibraryTab): String = when (tab) {
    LibraryTab.ALBUM_ARTISTS -> "Album Artists"
    LibraryTab.ARTISTS -> "Artists"
    LibraryTab.ALBUMS -> "Albums"
    LibraryTab.GENRES -> "Genres"
    LibraryTab.TRACKS -> "Tracks"
    LibraryTab.PLAYLISTS -> "Playlists"
}
