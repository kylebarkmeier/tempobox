package com.tempobox.playback

import android.media.AudioDeviceInfo
import com.tempobox.model.AudioOutputDevice

/**
 * Maps the platform's output [AudioDeviceInfo] list to the picker's
 * [AudioOutputDevice] entries: only routes a music app can sensibly target
 * (built-in speaker, wired, Bluetooth A2DP, USB, HDMI, hearing aids / BLE),
 * never telephony or mixing-internal devices.
 */
object AudioOutputs {

    private val PICKABLE_TYPES = setOf(
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_HEARING_AID,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
    )

    fun isPickable(type: Int): Boolean = type in PICKABLE_TYPES

    /** "Pixel Buds (Bluetooth)" / "This phone" style display name. */
    fun displayName(type: Int, productName: String): String {
        val kind = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> return "This device"
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            -> "Wired"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth"
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB"
            AudioDeviceInfo.TYPE_HDMI -> "HDMI"
            AudioDeviceInfo.TYPE_HEARING_AID -> "Hearing aid"
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> "Bluetooth LE"
            else -> ""
        }
        val name = productName.trim()
        return when {
            name.isEmpty() -> kind.ifEmpty { "Audio device" }
            kind.isEmpty() -> name
            else -> "$name ($kind)"
        }
    }

    /** The devices the picker offers, deduplicated by id, speaker first. */
    fun pickable(devices: List<AudioDeviceInfo>): List<AudioOutputDevice> = devices
        .filter { it.isSink && isPickable(it.type) }
        .distinctBy { it.id }
        .map { AudioOutputDevice(id = it.id, name = displayName(it.type, it.productName.toString())) }
        .sortedBy { if (it.name == "This device") "" else it.name.lowercase() }
}
