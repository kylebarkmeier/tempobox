package com.tempobox.ui.permissions

import android.Manifest
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** API-level mapping for the permission-aware greying. */
class PermissionGatesTest {

    @Test
    fun `bluetooth connect is only gated on API 31+`() {
        assertThat(PermissionGates.bluetoothConnectRequired(30)).isFalse()
        assertThat(PermissionGates.bluetoothConnectRequired(31)).isTrue()
        assertThat(PermissionGates.bluetoothConnectRequired(35)).isTrue()
    }

    @Test
    fun `post notifications is only gated on API 33+`() {
        assertThat(PermissionGates.postNotificationsRequired(32)).isFalse()
        assertThat(PermissionGates.postNotificationsRequired(33)).isTrue()
    }

    @Test
    fun `read permission matches the API level`() {
        assertThat(PermissionGates.readAudioPermission(32))
            .isEqualTo(Manifest.permission.READ_EXTERNAL_STORAGE)
        assertThat(PermissionGates.readAudioPermission(33))
            .isEqualTo(Manifest.permission.READ_MEDIA_AUDIO)
    }

    @Test
    fun `local network is only gated on API 37+`() {
        assertThat(PermissionGates.localNetworkRequired(36)).isFalse()
        assertThat(PermissionGates.localNetworkRequired(37)).isTrue()
    }

    @Test
    fun `all files access makes the library readable even without the media grant`() {
        assertThat(AppPermissions(allFilesAccess = true, readAudio = false).canReadLibrary).isTrue()
        assertThat(AppPermissions(allFilesAccess = false, readAudio = true).canReadLibrary).isTrue()
        assertThat(AppPermissions().canReadLibrary).isFalse()
    }
}
