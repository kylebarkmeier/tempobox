package com.tempobox

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.tempobox.library.LibraryPermissions
import com.tempobox.ui.AppRoot
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single-activity app. Requests the runtime permissions the library needs on
 * first launch (media read, notifications, Bluetooth); the deeper
 * "All files access" grant for tag editing/deletion is requested contextually
 * from Settings ▸ Library when the user first needs it.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // Results are re-checked reactively by the UI; nothing to do here.
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (!LibraryPermissions.hasRuntimePermissions(this)) {
            permissionLauncher.launch(LibraryPermissions.runtimePermissions().toTypedArray())
        }

        setContent {
            AppRoot()
        }
    }
}
