package com.tempobox.playback

import android.media.AudioDeviceInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith

/** Robolectric: AudioDeviceInfo cannot be constructed on the plain JVM. */
@RunWith(AndroidJUnit4::class)
class AudioOutputsTest {

    private fun device(id: Int, type: Int, name: String, sink: Boolean = true): AudioDeviceInfo =
        mockk {
            every { this@mockk.id } returns id
            every { this@mockk.type } returns type
            every { this@mockk.isSink } returns sink
            every { productName } returns name
        }

    @Test
    fun `only user-routable outputs are pickable`() {
        assertThat(AudioOutputs.isPickable(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)).isTrue()
        assertThat(AudioOutputs.isPickable(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)).isTrue()
        assertThat(AudioOutputs.isPickable(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)).isTrue()
        // Telephony and internal mixing routes never show up in a music picker.
        assertThat(AudioOutputs.isPickable(AudioDeviceInfo.TYPE_TELEPHONY)).isFalse()
        assertThat(AudioOutputs.isPickable(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)).isFalse()
        assertThat(AudioOutputs.isPickable(AudioDeviceInfo.TYPE_REMOTE_SUBMIX)).isFalse()
        assertThat(AudioOutputs.isPickable(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)).isFalse()
    }

    @Test
    fun `display names combine product name and transport kind`() {
        assertThat(AudioOutputs.displayName(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "Pixel 8"))
            .isEqualTo("This device")
        assertThat(AudioOutputs.displayName(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Pixel Buds"))
            .isEqualTo("Pixel Buds (Bluetooth)")
        assertThat(AudioOutputs.displayName(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, ""))
            .isEqualTo("Wired")
        assertThat(AudioOutputs.displayName(AudioDeviceInfo.TYPE_USB_HEADSET, "DAC-X"))
            .isEqualTo("DAC-X (USB)")
    }

    @Test
    fun `pickable filters sources and non-routable types, dedupes, speaker first`() {
        val devices = listOf(
            device(1, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Zeta Buds"),
            device(2, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "Phone"),
            device(2, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "Phone"), // duplicate id
            device(3, AudioDeviceInfo.TYPE_TELEPHONY, "Telephony"), // not pickable
            device(4, AudioDeviceInfo.TYPE_WIRED_HEADSET, "Mic", sink = false), // source
        )
        val outputs = AudioOutputs.pickable(devices)
        assertThat(outputs.map { it.id }).containsExactly(2, 1).inOrder()
        assertThat(outputs.first().name).isEqualTo("This device")
    }
}
