package com.tempobox.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tempobox.model.RuleField
import com.tempobox.model.RuleGroup
import com.tempobox.model.RuleOp
import com.tempobox.model.SmartRule
import com.tempobox.model.SmartRuleGroups

/**
 * Builder for "auto"/smart playlists (product spec): conditions on the common
 * tag fields, with </> for the numeric ones. Conditions combine with ALL
 * (AND) or ANY (OR) inside a group, and groups combine the same way at the
 * top level, so "(Genre is Rock OR Genre is Metal) AND Year > 1990" is
 * buildable. The dialog edits [SmartRuleGroups]'s two-level view directly:
 * [RuleGroup] and [SmartRule.Condition] are immutable, so every edit REPLACES
 * the element in the SnapshotStateList below and each recomposition scope
 * reading a group (incl. the Create button's enablement) is invalidated.
 * (Mutable fields bumped alongside a separate "revision" counter used to
 * leave the enablement check stale.)
 */
@Composable
fun SmartRuleBuilderDialog(
    suggestedName: String,
    initialRule: SmartRule?,
    onCreate: (name: String, rule: SmartRule) -> Unit,
    onDismiss: () -> Unit,
) {
    fun defaultCondition() = SmartRule.Condition(RuleField.GENRE, RuleOp.IS, "")

    var name by remember { mutableStateOf(suggestedName) }
    val seeded = remember { SmartRuleGroups.decompose(initialRule) }
    var matchAllGroups by remember { mutableStateOf(seeded.first) }
    val groups = remember {
        seeded.second
            .ifEmpty { listOf(RuleGroup(matchAll = true, listOf(defaultCondition()))) }
            .toMutableStateList()
    }

    val valid = name.isNotBlank() && groups.isNotEmpty() && groups.all { group ->
        group.conditions.isNotEmpty() && group.conditions.all { it.isComplete }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Auto playlist") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (groups.size > 1) {
                    MatchModeChips(
                        matchAll = matchAllGroups,
                        onChange = { matchAllGroups = it },
                        allLabel = "All groups (AND)",
                        anyLabel = "Any group (OR)",
                    )
                }
                groups.forEachIndexed { groupIndex, group ->
                    fun editConditions(edit: MutableList<SmartRule.Condition>.() -> Unit) {
                        groups[groupIndex] =
                            group.copy(conditions = group.conditions.toMutableList().apply(edit))
                    }

                    if (groupIndex > 0) HorizontalDivider()
                    GroupHeader(
                        number = (groupIndex + 1).takeIf { groups.size > 1 },
                        matchAll = group.matchAll,
                        onMatchAll = { all -> groups[groupIndex] = group.copy(matchAll = all) },
                        onRemove = { groups.removeAt(groupIndex) },
                    )
                    group.conditions.forEachIndexed { index, condition ->
                        ConditionRow(
                            condition = condition,
                            onChange = { editConditions { this[index] = it } },
                            // A group's last condition can only go by removing
                            // the whole group (header button), so a lone
                            // condition in a lone group can never be removed.
                            removable = group.conditions.size > 1,
                            onRemove = { editConditions { removeAt(index) } },
                        )
                    }
                    OutlinedButton(onClick = { editConditions { add(defaultCondition()) } }) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Text("Add condition")
                    }
                }
                OutlinedButton(onClick = {
                    groups.add(RuleGroup(matchAll = true, listOf(defaultCondition())))
                }) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text("Add group")
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    val rule = SmartRuleGroups.compose(
                        matchAllGroups,
                        groups.map { group ->
                            group.copy(conditions = group.conditions.map { it.copy(value = it.value.trim()) })
                        },
                    )
                    if (rule != null) onCreate(name.trim(), rule)
                },
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** One condition: field and operator pickers, the value, and a remove button. */
@Composable
private fun ConditionRow(
    condition: SmartRule.Condition,
    onChange: (SmartRule.Condition) -> Unit,
    removable: Boolean,
    onRemove: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        EnumDropdown(
            label = condition.field.label,
            options = RuleField.entries.map { it.label },
            modifier = Modifier.weight(1.2f),
        ) { picked ->
            val field = RuleField.entries[picked]
            val op = if (condition.op in RuleOp.operatorsFor(field)) condition.op else RuleOp.IS
            onChange(condition.copy(field = field, op = op))
        }
        EnumDropdown(
            label = condition.op.label,
            options = RuleOp.operatorsFor(condition.field).map { it.label },
            modifier = Modifier.weight(0.9f),
        ) { picked ->
            onChange(condition.copy(op = RuleOp.operatorsFor(condition.field)[picked]))
        }
        OutlinedTextField(
            value = condition.value,
            onValueChange = { onChange(condition.copy(value = it)) },
            singleLine = true,
            modifier = Modifier.weight(1.1f),
            placeholder = { Text(valuePlaceholder(condition.field)) },
        )
        IconButton(onClick = onRemove, enabled = removable) {
            Icon(Icons.Filled.Close, contentDescription = "Remove condition")
        }
    }
}

/**
 * A group's label, remove button, and ALL/ANY chips. Two rows, because one
 * row cannot hold the label, both chips, and the remove button on a narrow
 * phone (the weighted remove button would measure at zero width).
 *
 * @param number 1-based group number, or null when it is the only group
 *   (no label, no remove button; a lone group is removed via its conditions).
 */
@Composable
private fun GroupHeader(
    number: Int?,
    matchAll: Boolean,
    onMatchAll: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    if (number != null) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Group $number", style = MaterialTheme.typography.labelLarge)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                IconButton(onClick = onRemove) {
                    Icon(Icons.Filled.Close, contentDescription = "Remove group $number")
                }
            }
        }
    }
    MatchModeChips(
        matchAll = matchAll,
        onChange = onMatchAll,
        allLabel = "Match ALL (AND)",
        anyLabel = "Match ANY (OR)",
    )
}

/** The AND/OR two-chip toggle, shared by the top level and every group. */
@Composable
private fun MatchModeChips(
    matchAll: Boolean,
    onChange: (Boolean) -> Unit,
    allLabel: String,
    anyLabel: String,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = matchAll,
            onClick = { onChange(true) },
            label = { Text(allLabel) },
        )
        FilterChip(
            selected = !matchAll,
            onClick = { onChange(false) },
            label = { Text(anyLabel) },
        )
    }
}

@Composable
private fun EnumDropdown(
    label: String,
    options: List<String>,
    modifier: Modifier = Modifier,
    onPick: (Int) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        OutlinedButton(onClick = { open = true }) {
            Text(label, maxLines = 1)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        open = false
                        onPick(index)
                    },
                )
            }
        }
    }
}

private fun valuePlaceholder(field: RuleField): String = when (field) {
    RuleField.YEAR -> "e.g. 1990"
    RuleField.RATING -> "0-5"
    RuleField.TRACK_NUMBER, RuleField.DISC_NUMBER -> "e.g. 1"
    else -> "value"
}
