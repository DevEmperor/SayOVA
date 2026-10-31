package net.devemperor.sayova.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.util.Log
import net.devemperor.sayova.Diagnostics

/**
 * How loud Sayova speaks. Its voice plays as media ([SpeechPlayer.ATTRIBUTES]), the one stream any app
 * may set: watches like the Galaxy Watch6 keep a separate assistant volume, but only system apps may
 * change it. So Sayova keeps a volume of its own, puts it on the media stream for the length of a
 * conversation ([begin]) and gives the media volume back afterwards ([end]); the media volume the
 * user chose for everything else, often silent on the watch speaker, stays theirs.
 */
class SpeechVolume(context: Context, private val call: Boolean = false) {
    private val audio = context.getSystemService(AudioManager::class.java)
    /** In call mode (see CallAudio) the speaker follows the call volume, otherwise the media volume. */
    private val stream = if (call) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC
    private var mediaBefore = -1

    val min: Int = audio.getStreamMinVolume(stream)
    val max: Int = audio.getStreamMaxVolume(stream)

    fun current(): Int = audio.getStreamVolume(stream)

    /** One step louder (+1) or quieter (-1), without the system's own volume panel; returns the volume now. */
    fun step(direction: Int): Int {
        set(current() + direction)
        return current()
    }

    /**
     * Start of a conversation: puts Sayova's own volume ([saved], or the first time as loud as the
     * watch's assistant speaks) on the media stream. Never silent, so an answer is always heard.
     */
    fun begin(saved: Int) {
        mediaBefore = current()
        // The first time in call mode, as loud as the user has their calls.
        val level = saved.takeIf { it >= 0 } ?: (if (call) current() else assistantLevel()) ?: ((min + max) / 2)
        set(level.coerceAtLeast(min + 1))
    }

    /** End of a conversation: gives the media volume back and returns Sayova's own, to keep for next time. */
    fun end(): Int {
        val own = current()
        if (mediaBefore >= 0) set(mediaBefore)
        mediaBefore = -1
        return own
    }

    private fun set(index: Int) {
        runCatching { audio.setStreamVolume(stream, index.coerceIn(min, max), 0) }
            .onFailure { Log.w("Sayova", "Media volume not changeable: ${it.message}") }
        if (Diagnostics.enabled) Log.d("SayovaEvent", "volume ${current()} of $min..$max")
    }

    /** The assistant's volume on the media scale; null where it is the media volume anyway or unreadable. */
    private fun assistantLevel(): Int? = runCatching {
        val stream = ASSISTANT.volumeControlStream
        if (stream == this.stream) return null
        audio.getStreamVolume(stream) * max / audio.getStreamMaxVolume(stream)
    }.getOrNull()

    private companion object {
        val ASSISTANT: AudioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).build()
    }
}
