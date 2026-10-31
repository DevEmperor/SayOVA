package net.devemperor.sayova.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/**
 * Knows whether Sayova's voice goes to headphones. On the watch speaker the microphone, a few
 * centimetres away, hears every answer; through headphones it does not, so only then may the user's
 * voice cut an answer short.
 */
class Headphones(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)

    @Volatile var connected: Boolean = check()
        private set

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            connected = check()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            connected = check()
        }
    }

    fun start() = audio.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))

    fun stop() = audio.unregisterAudioDeviceCallback(callback)

    private fun check(): Boolean = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in HEADPHONE_TYPES }

    private companion object {
        val HEADPHONE_TYPES = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_HEARING_AID,
            26, // AudioDeviceInfo.TYPE_BLE_HEADSET, API 31
        )
    }
}
