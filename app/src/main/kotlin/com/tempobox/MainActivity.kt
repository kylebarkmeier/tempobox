package com.tempobox

import android.app.SearchManager
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.tempobox.library.LibraryPermissions
import com.tempobox.playback.VoiceActions
import com.tempobox.ui.AppRoot
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Single-activity app. Requests the runtime permissions the library needs on
 * first launch (media read, notifications, Bluetooth); the deeper
 * "All files access" grant for tag editing/deletion is requested contextually
 * from Settings ▸ Library when the user first needs it.
 *
 * Also the entry point for assistant voice playback: "play X on TempoBox"
 * arrives as a MEDIA_PLAY_FROM_SEARCH intent (the filter is what lets the
 * assistant target the app by name at all) and is handed to [VoiceActions],
 * the same resolver the in-app dialog uses.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var voiceActions: VoiceActions

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            // The UI re-checks grants on resume (permission-aware greying);
            // a denial is still worth a trace when "the library is empty".
            val denied = results.filterValues { !it }.keys
            if (denied.isNotEmpty()) {
                Log.w(TAG, "Permissions denied at first launch: ${denied.joinToString()}")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (!LibraryPermissions.hasRuntimePermissions(this)) {
            permissionLauncher.launch(LibraryPermissions.runtimePermissions().toTypedArray())
        }

        // Only on the first creation: a rotation or a restore recreates the
        // activity with the same intent, and the request must not play again.
        if (savedInstanceState == null) handleVoicePlayIntent(intent)

        setContent {
            AppRoot()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleVoicePlayIntent(intent)
    }

    /** The VoiceLog line (source `assistant-intent`) records the request and what it played. */
    private fun handleVoicePlayIntent(intent: Intent?) {
        if (intent?.action != MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) return
        voiceActions.playFromAssistant(intent.getStringExtra(SearchManager.QUERY).orEmpty())
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}
