package net.devemperor.sayova.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import net.devemperor.sayova.Diagnostics

/**
 * Puts the watch into call mode for a conversation over its speaker. Only then does the platform's
 * echo canceller, the one phone calls on the watch use, know what the speaker plays and take it out of
 * the microphone: played as media, Sayova's voice reached the microphone louder than the user, a few
 * centimetres from the speaker against an arm's length away, and no comparison of levels could tell
 * the user apart from it.
 */
class CallAudio(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var modeBefore = AudioManager.MODE_NORMAL
    private var active = false

    fun begin() {
        modeBefore = audio.mode
        active = runCatching { audio.mode = AudioManager.MODE_IN_COMMUNICATION }.isSuccess
        // A watch has no earpiece, but call audio must still be sent to the speaker explicitly on some.
        if (Build.VERSION.SDK_INT >= 31) {
            audio.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                ?.let { speaker -> runCatching { audio.setCommunicationDevice(speaker) } }
        }
        if (Diagnostics.enabled) Log.d("SayovaEvent", "call mode ${if (active) "on" else "refused"}, mode ${audio.mode}")
    }

    fun end() {
        if (!active) return
        if (Build.VERSION.SDK_INT >= 31) runCatching { audio.clearCommunicationDevice() }
        runCatching { audio.mode = modeBefore }
        active = false
    }
}
