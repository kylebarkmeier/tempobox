package com.tempobox.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.tempobox.model.TagData
import com.tempobox.model.TagEditForm
import com.tempobox.model.TagField

/**
 * "Edit ID3 tag(s)" modal.
 *
 * Fields are seeded from [form]: for a single track every field is prefilled
 * with the current tag; in a bulk edit a field is prefilled when all selected
 * tracks share the value and shows a "Multiple values" hint when they differ.
 * Save applies [TagEditForm.deriveEdits], so ONLY fields the user actually
 * changed are written — untouched fields (including mixed-value ones left
 * empty) keep each track's existing value. Numeric fields validate as integers
 * before Save enables.
 */
@Composable
fun TagEditorDialog(
    subjectLabel: String,
    trackCount: Int,
    form: TagEditForm,
    onApply: (TagData) -> Unit,
    onDismiss: () -> Unit,
) {
    val bulk = trackCount > 1
    var title by remember { mutableStateOf(form.title.value) }
    var artist by remember { mutableStateOf(form.artist.value) }
    var albumArtist by remember { mutableStateOf(form.albumArtist.value) }
    var album by remember { mutableStateOf(form.album.value) }
    var genre by remember { mutableStateOf(form.genre.value) }
    var year by remember { mutableStateOf(form.year.value) }
    var trackNo by remember { mutableStateOf(form.trackNumber.value) }
    var discNo by remember { mutableStateOf(form.discNumber.value) }

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
                        "Shared values are pre-filled. Only fields you change are " +
                            "applied to all $trackCount tracks.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Field("Title", title, form.title) { title = it }
                }
                Field("Artist", artist, form.artist) { artist = it }
                Field("Album artist", albumArtist, form.albumArtist) { albumArtist = it }
                Field("Album", album, form.album) { album = it }
                Field("Genre", genre, form.genre) { genre = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("Year", year, form.year, yearValid, Modifier.weight(1f)) { year = it }
                    if (!bulk) {
                        NumberField("Track #", trackNo, form.trackNumber, trackNoValid, Modifier.weight(1f)) { trackNo = it }
                        NumberField("Disc #", discNo, form.discNumber, discNoValid, Modifier.weight(1f)) { discNo = it }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = yearValid && trackNoValid && discNoValid,
                onClick = {
                    // Dirty-field tracking: only fields whose text differs from
                    // the seed end up non-null; everything else stays unchanged
                    // on every track.
                    onApply(
                        form.deriveEdits(
                            title = title,
                            artist = artist,
                            albumArtist = albumArtist,
                            album = album,
                            genre = genre,
                            year = year,
                            trackNumber = trackNo,
                            discNumber = discNo,
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** "Multiple values" hint under a field whose selected tracks disagree. */
private fun mixedHint(seed: TagField, currentText: String): (@Composable () -> Unit)? =
    if (seed.isMixed && currentText.isBlank()) {
        { Text("Multiple values — left unchanged") }
    } else {
        null
    }

@Composable
private fun Field(label: String, value: String, seed: TagField, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = if (seed.isMixed) ({ Text("Multiple values") }) else null,
        supportingText = mixedHint(seed, value),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    seed: TagField,
    valid: Boolean,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = if (seed.isMixed) ({ Text("Multiple values") }) else null,
        supportingText = mixedHint(seed, value),
        singleLine = true,
        isError = !valid,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}
