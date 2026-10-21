package com.tempobox.ui.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tempobox.library.LibraryPermissions

/**
 * Snapshot of the grants the UI gates on. "Granted" here means "the feature
 * can work": a permission that an older Android version does not have (e.g.
 * BLUETOOTH_CONNECT below API 31) reads as granted.
 */
data class AppPermissions(
    val allFilesAccess: Boolean = false,
    val readAudio: Boolean = false,
    val postNotifications: Boolean = false,
    val bluetoothConnect: Boolean = false,
    val localNetwork: Boolean = false,
) {
    /** True when the scanner can read the library folders at all. */
    val canReadLibrary: Boolean get() = readAudio || allFilesAccess
}

/** Which gate applies on which API level. Pure, so the mapping is unit-tested. */
object PermissionGates {
    /** Receiving ACL_CONNECTED (start-on-connect) needs BLUETOOTH_CONNECT on 31+. */
    fun bluetoothConnectRequired(sdkInt: Int): Boolean = sdkInt >= Build.VERSION_CODES.S

    fun postNotificationsRequired(sdkInt: Int): Boolean = sdkInt >= Build.VERSION_CODES.TIRAMISU

    /**
     * Android 17 (API 37) gates LAN sockets behind this runtime permission;
     * without it, SSDP discovery sends fail with EPERM. Not in
     * Manifest.permission under our compileSdk, hence the literal.
     */
    const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

    fun localNetworkRequired(sdkInt: Int): Boolean = sdkInt >= 37

    /** The permission that lets the scanner read audio files on this API level. */
    fun readAudioPermission(sdkInt: Int): String =
        if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
}

/** Current grants, checked fresh every call. */
fun checkAppPermissions(context: Context): AppPermissions {
    val sdk = Build.VERSION.SDK_INT
    fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    return AppPermissions(
        allFilesAccess = LibraryPermissions.hasAllFilesAccess(),
        readAudio = granted(PermissionGates.readAudioPermission(sdk)),
        postNotifications = !PermissionGates.postNotificationsRequired(sdk) ||
            granted(Manifest.permission.POST_NOTIFICATIONS),
        bluetoothConnect = !PermissionGates.bluetoothConnectRequired(sdk) ||
            granted(Manifest.permission.BLUETOOTH_CONNECT),
        localNetwork = !PermissionGates.localNetworkRequired(sdk) ||
            granted(PermissionGates.ACCESS_LOCAL_NETWORK),
    )
}

/**
 * Live permission state for gated UI. Re-checked on every ON_RESUME, so a
 * grant made in the system settings (or a runtime dialog) un-greys the UI as
 * soon as the user comes back.
 */
@Composable
fun rememberAppPermissions(): State<AppPermissions> {
    val context = LocalContext.current
    val state = remember { mutableStateOf(checkAppPermissions(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                state.value = checkAppPermissions(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return state
}
