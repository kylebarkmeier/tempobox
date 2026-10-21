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

/**
 * Toggle row. When [gateReason] is set, the feature cannot work (missing
 * permission): the row renders greyed with the reason as its subtitle, the
 * switch is disabled, and tapping the row runs [onGateClick] (which should
 * request the permission or open the right system screen).
 */
@Composable
fun SwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    gateReason: String? = null,
    onGateClick: (() -> Unit)? = null,
    onToggle: (Boolean) -> Unit,
) {
    val gated = gateReason != null
    val titleColor =
        if (gated) MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA)
        else MaterialTheme.colorScheme.onSurface
    val supporting = if (gated) gateReason else subtitle
    ListItem(
        headlineContent = { Text(title, color = titleColor) },
        supportingContent = supporting?.let { { Text(it) } },
        trailingContent = {
            Switch(checked = !gated && checked, onCheckedChange = onToggle, enabled = !gated)
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (gated) onGateClick?.invoke() else onToggle(!checked)
            },
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

/**
 * Plain tappable row (buttons like "Rescan library"). When [gateReason] is
 * set, the action cannot work (missing permission): the row renders greyed
 * with the reason as its subtitle, and tapping it runs [onGateClick] instead
 * of [onClick].
 */
@Composable
fun ActionRow(
    title: String,
    subtitle: String? = null,
    destructive: Boolean = false,
    gateReason: String? = null,
    onGateClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val gated = gateReason != null
    val titleColor = when {
        gated -> MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA)
        destructive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    val supporting = if (gated) gateReason else subtitle
    ListItem(
        headlineContent = { Text(title, color = titleColor) },
        supportingContent = supporting?.let { { Text(it) } },
        modifier = Modifier
            .fillMaxWidth()
            .clickable { if (gated) onGateClick?.invoke() else onClick() },
    )
}

/** Material's standard disabled-content alpha. */
internal const val DISABLED_ALPHA = 0.38f

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
