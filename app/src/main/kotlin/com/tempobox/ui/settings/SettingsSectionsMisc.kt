package com.tempobox.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.tempobox.model.Corner
import com.tempobox.model.CornerAction
import com.tempobox.model.MediaButton
import com.tempobox.model.MediaButtonAction
import com.tempobox.model.ThemeConfig
import com.tempobox.model.TrackInfoField
import com.tempobox.model.VolumeTapAction
import com.tempobox.ui.nowplaying.cornerLabel

// ---------------------------------------------------------------- Now Playing

/** Settings ▸ Now Playing: corner buttons + track info display (spec). */
@Composable
fun NowPlayingSection(viewModel: SettingsViewModel) {
    val settings by viewModel.settings.collectAsState()
    val actions = CornerAction.entries

    SettingsSection("Corner buttons around album art") {
        Corner.entries.forEach { corner ->
            val current = settings.nowPlaying.cornerActions[corner] ?: CornerAction.NONE
            DropdownRow(
                title = cornerName(corner),
                currentLabel = cornerLabel(current),
                options = actions.map { cornerLabel(it) },
            ) { picked ->
                viewModel.updateNowPlaying {
                    it.copy(cornerActions = it.cornerActions + (corner to actions[picked]))
                }
            }
        }
    }

    SettingsDivider()
    SettingsSection("Track info shown under the title") {
        TrackInfoField.entries.forEach { field ->
            val enabled = field in settings.nowPlaying.trackInfoFields
            ListItem(
                headlineContent = { Text(infoFieldName(field)) },
                trailingContent = {
                    Checkbox(
                        checked = enabled,
                        onCheckedChange = { on ->
                            viewModel.updateNowPlaying { np ->
                                np.copy(
                                    trackInfoFields = if (on) {
                                        np.trackInfoFields + field
                                    } else {
                                        np.trackInfoFields - field
                                    },
                                )
                            }
                        },
                    )
                },
            )
        }
    }
}

private fun cornerName(corner: Corner): String = when (corner) {
    Corner.TOP_LEFT -> "Top left"
    Corner.TOP_RIGHT -> "Top right"
    Corner.BOTTOM_LEFT -> "Bottom left"
    Corner.BOTTOM_RIGHT -> "Bottom right"
}

private fun infoFieldName(field: TrackInfoField): String = when (field) {
    TrackInfoField.ARTIST -> "Artist"
    TrackInfoField.ALBUM -> "Album"
    TrackInfoField.YEAR -> "Year"
    TrackInfoField.GENRE -> "Genre"
    TrackInfoField.FORMAT -> "Format / sample rate"
    TrackInfoField.BITRATE -> "Bitrate"
    TrackInfoField.RATING -> "Rating"
    TrackInfoField.PLAY_COUNT -> "Play count"
}

// ---------------------------------------------------------------- Queue

@Composable
fun QueueSection(viewModel: SettingsViewModel) {
    val settings by viewModel.settings.collectAsState()

    SettingsSection("Play queue") {
        SwitchRow(
            title = "Restore queue on restart",
            subtitle = "Reload the queue, position and repeat mode after the app restarts",
            checked = settings.queue.persistQueue,
            onToggle = { on -> viewModel.updateQueue { it.copy(persistQueue = on) } },
        )
        SwitchRow(
            title = "Confirm before clearing",
            subtitle = "Ask before the Clear button empties the queue",
            checked = settings.queue.confirmClearQueue,
            onToggle = { on -> viewModel.updateQueue { it.copy(confirmClearQueue = on) } },
        )
        SwitchRow(
            title = "Allow duplicates",
            subtitle = "Let the same track be added to the queue more than once",
            checked = settings.queue.allowDuplicates,
            onToggle = { on -> viewModel.updateQueue { it.copy(allowDuplicates = on) } },
        )
    }
}

// ---------------------------------------------------------------- Bluetooth

/** Settings ▸ Bluetooth: autoplay, volume triple-tap gestures, remapping. */
@Composable
fun BluetoothSection(viewModel: SettingsViewModel) {
    val settings by viewModel.settings.collectAsState()
    val tapActions = VolumeTapAction.entries
    val remapActions = MediaButtonAction.entries

    SettingsSection("Connection") {
        SwitchRow(
            title = "Start playback on connect",
            subtitle = "Resume automatically when a Bluetooth device or headset connects",
            checked = settings.bluetooth.startOnConnect,
            onToggle = { on -> viewModel.updateBluetooth { it.copy(startOnConnect = on) } },
        )
    }

    SettingsDivider()
    SettingsSection("Volume button gestures (screen off)") {
        Text(
            "Triple-press a volume key while the screen is off to trigger an action. " +
                "The volume level is restored automatically. Note: gestures can't start " +
                "while volume is already at its minimum or maximum.",
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        DropdownRow(
            title = "Triple-tap volume up",
            currentLabel = tapActionName(settings.bluetooth.volumeUpTripleTap),
            options = tapActions.map { tapActionName(it) },
        ) { picked ->
            viewModel.updateBluetooth { it.copy(volumeUpTripleTap = tapActions[picked]) }
        }
        DropdownRow(
            title = "Triple-tap volume down",
            currentLabel = tapActionName(settings.bluetooth.volumeDownTripleTap),
            options = tapActions.map { tapActionName(it) },
        ) { picked ->
            viewModel.updateBluetooth { it.copy(volumeDownTripleTap = tapActions[picked]) }
        }
    }

    SettingsDivider()
    SettingsSection("Headset button remapping") {
        MediaButton.entries.forEach { button ->
            val current = settings.bluetooth.buttonRemap[button] ?: MediaButtonAction.DEFAULT
            DropdownRow(
                title = mediaButtonName(button),
                currentLabel = remapActionName(current),
                options = remapActions.map { remapActionName(it) },
            ) { picked ->
                viewModel.updateBluetooth {
                    it.copy(buttonRemap = it.buttonRemap + (button to remapActions[picked]))
                }
            }
        }
    }
}

private fun tapActionName(action: VolumeTapAction): String = when (action) {
    VolumeTapAction.NONE -> "Nothing"
    VolumeTapAction.NEXT_TRACK -> "Next track"
    VolumeTapAction.PREVIOUS_TRACK -> "Previous track"
    VolumeTapAction.PLAY_PAUSE -> "Play / pause"
    VolumeTapAction.STOP -> "Stop"
}

private fun mediaButtonName(button: MediaButton): String = when (button) {
    MediaButton.PLAY_PAUSE -> "Play/pause button"
    MediaButton.NEXT -> "Next button"
    MediaButton.PREVIOUS -> "Previous button"
    MediaButton.STOP -> "Stop button"
}

private fun remapActionName(action: MediaButtonAction): String = when (action) {
    MediaButtonAction.DEFAULT -> "Default"
    MediaButtonAction.PLAY_PAUSE -> "Play / pause"
    MediaButtonAction.NEXT_TRACK -> "Next track"
    MediaButtonAction.PREVIOUS_TRACK -> "Previous track"
    MediaButtonAction.STOP -> "Stop"
    MediaButtonAction.NONE -> "Do nothing"
}

// ---------------------------------------------------------------- Shuffle

/** Settings ▸ Shuffle: anti-repeat default ON + rating bias (spec). */
@Composable
fun ShuffleSection(viewModel: SettingsViewModel) {
    val settings by viewModel.settings.collectAsState()

    SettingsSection("Shuffle behavior") {
        SwitchRow(
            title = "Anti-repeat shuffle",
            subtitle = "Spread out repeats of the same track, album and artist as far as possible",
            checked = settings.shuffle.antiRepeat,
            onToggle = { on -> viewModel.updateShuffle { it.copy(antiRepeat = on) } },
        )
        SwitchRow(
            title = "Favor higher-rated tracks",
            subtitle = "Bias shuffle toward tracks with more stars (5★ strongest)",
            checked = settings.shuffle.ratingBias,
            onToggle = { on -> viewModel.updateShuffle { it.copy(ratingBias = on) } },
        )
    }
}

// ---------------------------------------------------------------- Last.fm

@Composable
fun LastFmSection(viewModel: SettingsViewModel) {
    val settings by viewModel.settings.collectAsState()
    val status by viewModel.lastFmStatus.collectAsState()

    SettingsSection("Scrobble via another app") {
        SwitchRow(
            title = "Hand scrobbles to a scrobbler app",
            subtitle = "Broadcast played tracks for apps like Pano Scrobbler or Simple " +
                "Scrobbler to pick up — no Last.fm login needed in TempoBox. (The official " +
                "Last.fm app only watches its own list of players, so use a scrobbler app.)",
            checked = settings.lastFm.broadcastScrobbles,
            onToggle = { on -> viewModel.updateLastFm { it.copy(broadcastScrobbles = on) } },
        )
    }

    SettingsDivider()
    SettingsSection("Built-in scrobbling") {
        SwitchRow(
            title = "Enable scrobbling",
            subtitle = "Send played tracks to Last.fm directly (offline plays are queued); " +
                "needs the API credentials and sign-in below",
            checked = settings.lastFm.scrobbleEnabled,
            onToggle = { on -> viewModel.updateLastFm { it.copy(scrobbleEnabled = on) } },
        )
        SwitchRow(
            title = "Update \"Now Playing\"",
            subtitle = "Also show the current track live on your profile",
            checked = settings.lastFm.updateNowPlaying,
            onToggle = { on -> viewModel.updateLastFm { it.copy(updateNowPlaying = on) } },
        )
    }

    SettingsDivider()
    SettingsSection("API credentials") {
        Text(
            "Create a free API account at last.fm/api/account/create, then paste the key and secret here.",
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        var apiKey by rememberSaveable(settings.lastFm.apiKey) { mutableStateOf(settings.lastFm.apiKey) }
        var apiSecret by rememberSaveable(settings.lastFm.apiSecret) { mutableStateOf(settings.lastFm.apiSecret) }
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("API key") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )
        OutlinedTextField(
            value = apiSecret,
            onValueChange = { apiSecret = it },
            label = { Text("Shared secret") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )
        TextButton(
            onClick = {
                viewModel.updateLastFm { it.copy(apiKey = apiKey.trim(), apiSecret = apiSecret.trim()) }
            },
            modifier = Modifier.padding(horizontal = 8.dp),
        ) { Text("Save credentials") }
    }

    SettingsDivider()
    SettingsSection("Account") {
        if (settings.lastFm.sessionKey.isNotBlank()) {
            ListItem(
                headlineContent = { Text("Signed in as ${settings.lastFm.username}") },
                supportingContent = { Text("Tap to sign out") },
                modifier = Modifier.clickable { viewModel.lastFmLogout() },
            )
        } else {
            var username by rememberSaveable { mutableStateOf("") }
            var password by rememberSaveable { mutableStateOf("") }
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Last.fm username") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
            TextButton(
                onClick = { viewModel.lastFmLogin(username.trim(), password) },
                modifier = Modifier.padding(horizontal = 8.dp),
            ) { Text("Sign in") }
        }
        status?.let {
            Text(
                it,
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

// ---------------------------------------------------------------- Theme

/** Settings ▸ Theme: customizable colors + dark mode (spec). */
@Composable
fun ThemeSection(viewModel: SettingsViewModel) {
    val settings by viewModel.settings.collectAsState()
    val darkModes = ThemeConfig.DarkMode.entries

    SettingsSection("Appearance") {
        DropdownRow(
            title = "Dark mode",
            currentLabel = darkModeName(settings.theme.darkMode),
            options = darkModes.map { darkModeName(it) },
        ) { picked -> viewModel.updateTheme { it.copy(darkMode = darkModes[picked]) } }
        SwitchRow(
            title = "Material You dynamic color",
            subtitle = "Use system wallpaper colors (Android 12+); overrides the colors below",
            checked = settings.theme.useDynamicColor,
            onToggle = { on -> viewModel.updateTheme { it.copy(useDynamicColor = on) } },
        )
    }

    SettingsDivider()
    SettingsSection("Custom colors") {
        ColorPickerRow("Primary", settings.theme.primaryArgb) { argb ->
            viewModel.updateTheme { it.copy(primaryArgb = argb) }
        }
        ColorPickerRow("Secondary", settings.theme.secondaryArgb) { argb ->
            viewModel.updateTheme { it.copy(secondaryArgb = argb) }
        }
        ColorPickerRow("Tertiary", settings.theme.tertiaryArgb) { argb ->
            viewModel.updateTheme { it.copy(tertiaryArgb = argb) }
        }
    }
}

private fun darkModeName(mode: ThemeConfig.DarkMode): String = when (mode) {
    ThemeConfig.DarkMode.SYSTEM -> "Follow system"
    ThemeConfig.DarkMode.LIGHT -> "Light"
    ThemeConfig.DarkMode.DARK -> "Dark"
}

/** Preset swatch row (tap to apply) with the current color highlighted. */
@Composable
private fun ColorPickerRow(label: String, currentArgb: Long, onPick: (Long) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(label, style = androidx.compose.material3.MaterialTheme.typography.bodyLarge)
        Row {
            SWATCHES.forEach { argb ->
                val selected = argb == currentArgb
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .padding(4.dp)
                        .size(if (selected) 36.dp else 30.dp)
                        .clip(CircleShape)
                        .background(Color(argb.toInt()))
                        .clickable { onPick(argb) },
                )
            }
        }
    }
}

/** A tasteful default palette; any ARGB long works via ThemeConfig. */
private val SWATCHES: List<Long> = listOf(
    0xFF6750A4, // material baseline purple
    0xFF4A3A8C, // TempoBox indigo
    0xFF00696D, // teal
    0xFF8B5000, // amber
    0xFF9C4146, // maroon
    0xFF2E6C2F, // green
    0xFF00639B, // blue
    0xFF625B71, // muted violet
)
