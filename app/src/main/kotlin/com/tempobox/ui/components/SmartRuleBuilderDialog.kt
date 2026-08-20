package com.tempobox.ui.components

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tempobox.model.RuleField
import com.tempobox.model.RuleOp
import com.tempobox.model.SmartRule

/**
 * Builder for "auto"/smart playlists (product spec): conditions on
 * Album Artist / Artist / Genre / Year / Rating, with </> for the numeric
 * fields, combined with ALL (AND) or ANY (OR).
 */
@Composable
fun SmartRuleBuilderDialog(
    suggestedName: String,
    initialRule: SmartRule?,
    onCreate: (name: String, rule: SmartRule) -> Unit,
    onDismiss: () -> Unit,
) {
    data class ConditionDraft(var field: RuleField, var op: RuleOp, var value: String)

    fun seedConditions(rule: SmartRule?): List<ConditionDraft> = when (rule) {
        is SmartRule.Condition -> listOf(ConditionDraft(rule.field, rule.op, rule.value))
        is SmartRule.AllOf -> rule.rules.filterIsInstance<SmartRule.Condition>()
            .map { ConditionDraft(it.field, it.op, it.value) }
        is SmartRule.AnyOf -> rule.rules.filterIsInstance<SmartRule.Condition>()
            .map { ConditionDraft(it.field, it.op, it.value) }
        null -> emptyList()
    }.ifEmpty { listOf(ConditionDraft(RuleField.GENRE, RuleOp.IS, "")) }

    var name by remember { mutableStateOf(suggestedName) }
    var matchAll by remember { mutableStateOf(initialRule !is SmartRule.AnyOf) }
    val conditions = remember { seedConditions(initialRule).toMutableStateList() }
    // Trigger recomposition on in-place edits of drafts.
    var revision by remember { mutableStateOf(0) }

    val valid = name.isNotBlank() && conditions.isNotEmpty() && conditions.all { draft ->
        draft.value.isNotBlank() && (!draft.field.isNumeric || draft.value.trim().toIntOrNull() != null)
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = matchAll,
                        onClick = { matchAll = true },
                        label = { Text("Match ALL (AND)") },
                    )
                    FilterChip(
                        selected = !matchAll,
                        onClick = { matchAll = false },
                        label = { Text("Match ANY (OR)") },
                    )
                }
                key(revision) {
                    conditions.forEachIndexed { index, draft ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            EnumDropdown(
                                label = fieldLabel(draft.field),
                                options = RuleField.entries.map { fieldLabel(it) },
                                modifier = Modifier.weight(1.2f),
                            ) { picked ->
                                draft.field = RuleField.entries[picked]
                                if (draft.op !in RuleOp.operatorsFor(draft.field)) {
                                    draft.op = RuleOp.IS
                                }
                                revision++
                            }
                            EnumDropdown(
                                label = opLabel(draft.op),
                                options = RuleOp.operatorsFor(draft.field).map { opLabel(it) },
                                modifier = Modifier.weight(0.9f),
                            ) { picked ->
                                draft.op = RuleOp.operatorsFor(draft.field)[picked]
                                revision++
                            }
                            OutlinedTextField(
                                value = draft.value,
                                onValueChange = { draft.value = it; revision++ },
                                singleLine = true,
                                modifier = Modifier.weight(1.1f),
                                placeholder = { Text(if (draft.field.isNumeric) "e.g. 1990" else "value") },
                            )
                            IconButton(
                                onClick = { conditions.removeAt(index); revision++ },
                                enabled = conditions.size > 1,
                            ) {
                                Icon(Icons.Filled.Close, contentDescription = "Remove condition")
                            }
                        }
                    }
                }
                OutlinedButton(onClick = {
                    conditions.add(ConditionDraft(RuleField.GENRE, RuleOp.IS, ""))
                    revision++
                }) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text("Add condition")
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    val leaves: List<SmartRule> = conditions.map {
                        SmartRule.Condition(it.field, it.op, it.value.trim())
                    }
                    val rule = when {
                        leaves.size == 1 -> leaves.first()
                        matchAll -> SmartRule.AllOf(leaves)
                        else -> SmartRule.AnyOf(leaves)
                    }
                    onCreate(name.trim(), rule)
                },
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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

private fun fieldLabel(field: RuleField): String = when (field) {
    RuleField.ALBUM_ARTIST -> "Album artist"
    RuleField.ARTIST -> "Artist"
    RuleField.GENRE -> "Genre"
    RuleField.YEAR -> "Year"
    RuleField.RATING -> "Rating"
}

private fun opLabel(op: RuleOp): String = when (op) {
    RuleOp.IS -> "is"
    RuleOp.IS_NOT -> "is not"
    RuleOp.CONTAINS -> "contains"
    RuleOp.LESS_THAN -> "<"
    RuleOp.GREATER_THAN -> ">"
}
