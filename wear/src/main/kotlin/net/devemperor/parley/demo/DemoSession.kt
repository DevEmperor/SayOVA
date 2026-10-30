package net.devemperor.parley.demo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import net.devemperor.parley.core.session.VoiceEvent
import net.devemperor.parley.core.session.VoiceSession
import kotlin.math.PI
import kotlin.math.sin

/**
 * A scripted conversation for emulator tests and store screenshots (debug builds only):
 * `adb shell am start -n net.devemperor.parley.debug/net.devemperor.parley.MainActivity --ez demo true`.
 * The "speech" is a quiet hum shaped like syllables, enough to move the orb.
 */
class DemoSession : VoiceSession {
    private val channel = Channel<VoiceEvent>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    override val events: Flow<VoiceEvent> = channel.receiveAsFlow()
    override val inputSampleRate = 16_000
    override val outputSampleRate = 24_000

    override fun connect() {
        scope.launch {
            delay(600)
            channel.send(VoiceEvent.Ready)
            delay(1_200)
            say("u1", "What's a quick stretch for my back after sitting all day?")
            delay(900)
            answer(
                "m1",
                "Try a seated twist. Sit tall, put your right hand on your left knee and gently turn " +
                    "left for five slow breaths, then switch sides. Want a second one for your shoulders?",
            )
        }
    }

    private suspend fun say(key: String, text: String) {
        text.split(" ").forEach { word ->
            channel.send(VoiceEvent.UserTranscript(key, "$word ", replace = false))
            delay(140)
        }
    }

    private suspend fun answer(key: String, text: String) {
        val words = text.split(" ")
        words.forEachIndexed { index, word ->
            channel.send(VoiceEvent.AssistantTranscript(key, if (index == 0) word else " $word"))
            channel.send(VoiceEvent.AssistantAudio(key, syllable(index)))
            delay(240)
        }
        channel.send(VoiceEvent.TurnComplete)
    }

    /** 260 ms of a soft vowel-like tone with a rise and fall, at 24 kHz. */
    private fun syllable(seed: Int): ByteArray {
        val samples = 24_000 * 260 / 1000
        val pitch = 150.0 + (seed % 5) * 12
        val out = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val envelope = sin(PI * i / samples)
            val value = (envelope * 3_000 * (sin(2 * PI * pitch * i / 24_000) + 0.4 * sin(4 * PI * pitch * i / 24_000))).toInt()
            out[2 * i] = (value and 0xFF).toByte()
            out[2 * i + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return out
    }

    override fun sendAudio(pcm: ByteArray) = Unit
    override fun endOfUserAudio() = Unit
    override fun sendText(text: String) = Unit
    override fun sendToolResult(callId: String, name: String, result: JsonObject) = Unit
    override fun interrupt(turnKey: String?, playedMs: Long) = Unit

    override fun close() {
        channel.trySend(VoiceEvent.Closed(null))
        channel.close()
        scope.cancel()
    }
}
