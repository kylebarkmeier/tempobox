package com.tempobox.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.tempobox.model.TagData

/**
 * "Edit ID3 tag(s)" modal.
 *
 * Single track: fields pre-filled from the file. Multiple tracks (bulk edit):
 * fields start blank and ONLY non-blank fields are written — so setting just
 * "Genre" on 20 tracks won't clobber their titles. Numeric fields validate as
 * integers before Save enables.
 */
@Composable
fun TagEditorDialog(
    subjectLabel: String,
    trackCount: Int,
    initial: TagData?,
    onApply: (TagData) -> Unit,
    onDismiss: () -> Unit,
) {
    val bulk = trackCount > 1
    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var artist by remember { mutableStateOf(initial?.artist.orEmpty()) }
    var albumArtist by remember { mutableStateOf(initial?.albumArtist.orEmpty()) }
    var album by remember { mutableStateOf(initial?.album.orEmpty()) }
    var genre by remember { mutableStateOf(initial?.genre.orEmpty()) }
    var year by remember { mutableStateOf(initial?.year?.toString().orEmpty()) }
    var trackNo by remember { mutableStateOf(initial?.trackNumber?.toString().orEmpty()) }
    var discNo by remember { mutableStateOf(initial?.discNumber?.toString().orEmpty()) }

    val yearValid = year.isBlank() || year.trim().toIntOrNull() != null
    val trackNoValid = trackNo.isBlank() || trackNo.trim().toIntOrNull() != null
    val discNoValid = discNo.isBlank() || discNo.trim().toIntOrNull() != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (bulk) "Edit tags — $trackCount tracks" else "Edit tags — $subjectLabel") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (bulk) {
                    Text(
                        "Only fields you fill in are applied to all $trackCount tracks.",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Field("Title", title) { title = it }
                }
                Field("Artist", artist) { artist = it }
                Field("Album artist", albumArtist) { albumArtist = it }
                Field("Album", album) { album = it }
                Field("Genre", genre) { genre = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("Year", year, yearValid, Modifier.weight(1f)) { year = it }
                    if (!bulk) {
                        NumberField("Track #", trackNo, trackNoValid, Modifier.weight(1f)) { trackNo = it }
                        NumberField("Disc #", discNo, discNoValid, Modifier.weight(1f)) { discNo = it }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = yearValid && trackNoValid && discNoValid,
                onClick = {
                    // Bulk edits translate blank → null (= leave unchanged);
                    // single edits write every visible field as-is.
                    fun keep(value: String): String? =
                        if (bulk) value.trim().ifBlank { null } else value.trim()
                    onApply(
                        TagData(
                            title = if (bulk) null else title.trim(),
                            artist = keep(artist),
                            albumArtist = keep(albumArtist),
                            album = keep(album),
                            genre = keep(genre),
                            year = year.trim().toIntOrNull(),
                            trackNumber = if (bulk) null else trackNo.trim().toIntOrNull(),
                            discNumber = if (bulk) null else discNo.trim().toIntOrNull(),
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    valid: Boolean,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = !valid,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}
