package com.tempobox.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Shared building blocks for the settings screens. */

@Composable
fun SettingsGroupTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
fun SwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = onToggle) },
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(!checked) },
    )
}

/** Row that opens a dropdown of [options]; shows the current label. */
@Composable
fun DropdownRow(
    title: String,
    currentLabel: String,
    options: List<String>,
    subtitle: String? = null,
    onPick: (Int) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = subtitle?.let { { Text(it) } },
            trailingContent = { Text(currentLabel, color = MaterialTheme.colorScheme.primary) },
            modifier = Modifier
                .fillMaxWidth()
                .clickable { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEachIndexed { index, label ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        open = false
                        onPick(index)
                    },
                )
            }
        }
    }
}

/** Plain tappable row (buttons like "Rescan library"). */
@Composable
fun ActionRow(
    title: String,
    subtitle: String? = null,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(
                title,
                color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        },
        supportingContent = subtitle?.let { { Text(it) } },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    )
}

@Composable
fun SettingsDivider() {
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
}

/** Section container. */
@Composable
fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column {
        SettingsGroupTitle(title)
        content()
    }
}
