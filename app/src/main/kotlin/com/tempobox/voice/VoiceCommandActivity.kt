package com.tempobox.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.tempobox.model.ThemeConfig
import com.tempobox.playback.PlayerConnection
import com.tempobox.playback.VoiceCommand
import com.tempobox.settings.SettingsRepository
import com.tempobox.ui.permissions.openAppSettings
import com.tempobox.ui.theme.TempoBoxTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Transient voice-command dialog: captures one utterance with the device's
 * [SpeechRecognizer], parses it through the unit-tested [VoiceCommand]
 * grammar, executes it via [PlayerConnection.execute], and finishes. Launched
 * from the Library top bar, the home-screen widget, and the Quick Settings
 * tile ([VoiceTileService]); Android Auto voice goes through the media
 * session's search instead (PlaybackService.onSearch).
 *
 * Needs no assistant: the recognizer is the device's own speech service.
 */
@AndroidEntryPoint
class VoiceCommandActivity : ComponentActivity() {

    @Inject lateinit var player: PlayerConnection
    @Inject lateinit var settingsRepository: SettingsRepository

    private var recognizer: SpeechRecognizer? = null

    /** What the dialog shows; mutated by the recognition listener (main thread). */
    private var state by mutableStateOf<VoiceState>(VoiceState.Starting)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // After repeated denials Android reports denial without showing a
            // dialog, so the denied state offers the system-settings path.
            if (granted) startListening() else state = VoiceState.PermissionDenied
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val settings by settingsRepository.settings.collectAsState(initial = null)
            TempoBoxTheme(config = settings?.theme ?: ThemeConfig()) {
                VoiceDialog(
                    state = state,
                    onRetry = ::beginOrRequestPermission,
                    onOpenSettings = { openAppSettings(this) },
                    onCancel = ::finish,
                )
            }
        }
        beginOrRequestPermission()
    }

    override fun onDestroy() {
        recognizer?.destroy()
        recognizer = null
        super.onDestroy()
    }

    private fun beginOrRequestPermission() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) startListening() else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            state = VoiceState.Error(
                "No speech recognition service on this device",
                retryable = false,
            )
            return
        }
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(recognitionListener)
            recognizer = it
        }
        state = VoiceState.Listening(heard = null)
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                )
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            },
        )
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            state = VoiceState.Listening(heard = null)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { state = VoiceState.Listening(heard = it) }
        }

        override fun onResults(results: Bundle?) {
            val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim().orEmpty()
            val command = VoiceCommand.parse(heard)
            if (command == null) {
                // One line per utterance so a "nothing happened" report can be
                // checked against what the recognizer actually produced.
                Log.i(TAG, "No command in '$heard'")
                state = VoiceState.Error(
                    if (heard.isBlank()) "Did not hear anything" else "No command in “$heard”",
                    retryable = true,
                )
                return
            }
            Log.i(TAG, "Voice command from '$heard': $command")
            player.execute(command)
            // The dialog is gone before the command takes effect; a toast
            // outlives the activity and names what was understood.
            Toast.makeText(this@VoiceCommandActivity, command.confirmation(), Toast.LENGTH_SHORT)
                .show()
            finish()
        }

        override fun onError(error: Int) {
            Log.w(TAG, "Speech recognition error $error")
            state = VoiceState.Error(recognizerErrorMessage(error), retryable = true)
        }

        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private companion object {
        const val TAG = "VoiceCommand"
    }
}

/** Dialog phases. */
internal sealed interface VoiceState {
    data object Starting : VoiceState
    data class Listening(val heard: String?) : VoiceState
    data class Error(val message: String, val retryable: Boolean) : VoiceState
    data object PermissionDenied : VoiceState
}

@Composable
private fun VoiceDialog(
    state: VoiceState,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    onCancel: () -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 6.dp,
            modifier = Modifier
                .padding(24.dp)
                .widthIn(max = 360.dp),
        ) {
            Column(
                Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Filled.Mic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp),
                )
                when (state) {
                    VoiceState.Starting -> Text("Starting…")

                    is VoiceState.Listening -> {
                        Text(
                            state.heard ?: "Listening…",
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                        )
                        Hint()
                        TextButton(onClick = onCancel) { Text("Cancel") }
                    }

                    is VoiceState.Error -> {
                        Text(
                            state.message,
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                        )
                        Hint()
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = onCancel) { Text("Close") }
                            if (state.retryable) {
                                TextButton(onClick = onRetry) { Text("Try again") }
                            }
                        }
                    }

                    VoiceState.PermissionDenied -> {
                        Text(
                            "Voice commands need the microphone. If no dialog " +
                                "appeared, allow Microphone under Permissions in " +
                                "the app's system settings.",
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = onCancel) { Text("Close") }
                            TextButton(onClick = onRetry) { Text("Allow") }
                            TextButton(onClick = onOpenSettings) { Text("Open settings") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Hint() {
    Text(
        "Try “play ‹artist, album or playlist›”, “next”, " +
            "“pause”, “shuffle on”, “repeat one”",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}
