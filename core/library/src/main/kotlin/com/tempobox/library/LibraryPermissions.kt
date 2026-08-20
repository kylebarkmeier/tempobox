package com.tempobox.library

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Central answer to "which permissions does the library need?".
 *
 * Reading: `READ_MEDIA_AUDIO` (API 33+) / `READ_EXTERNAL_STORAGE` (≤32).
 *
 * Writing (ID3 edits, deletes) in arbitrary user-chosen folders requires
 * *All files access* (`MANAGE_EXTERNAL_STORAGE`) on API 30+ — the same model
 * used by other file-managing music players. The app functions read-only
 * without it; the UI prompts when a write operation first needs it.
 */
object LibraryPermissions {

    /** Runtime (dialog) permissions to request at first launch. */
    fun runtimePermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.READ_MEDIA_AUDIO)
            add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    /** True when every runtime permission has been granted. */
    fun hasRuntimePermissions(context: Context): Boolean =
        runtimePermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    /** True when the app may modify/delete files anywhere in the library locations. */
    fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true // ≤29: WRITE_EXTERNAL_STORAGE via manifest covers it
        }

    /** Settings intent that lets the user grant All-files access to this app. */
    fun allFilesAccessIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.fromParts("package", context.packageName, null),
            )
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        }
}
