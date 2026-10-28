package com.tempobox.voice

import android.content.Context
import android.net.ConnectivityManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class VoiceConnectivityTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `an active network is online`() {
        assertThat(isOffline(context)).isFalse()
    }

    @Test
    fun `airplane mode with a live network is still online`() {
        // Wi-Fi re-enabled inside airplane mode serves the speech service.
        Settings.Global.putInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 1)
        assertThat(isOffline(context)).isFalse()
    }

    @Test
    fun `no active network is offline`() {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        shadowOf(connectivity).setActiveNetworkInfo(null)
        assertThat(isOffline(context)).isTrue()
    }
}
