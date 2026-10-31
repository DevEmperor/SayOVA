package net.devemperor.talova.audio

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.devemperor.talova.core.audio.Pcm
import net.devemperor.talova.core.cost.CostMeter
import net.devemperor.talova.core.provider.Provider
import net.devemperor.talova.core.session.SessionConfig
import net.devemperor.talova.core.session.Sessions
import net.devemperor.talova.core.session.VoiceEvent
import net.devemperor.talova.Diagnostics
import net.devemperor.talova.talova
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Voice samples for the voice list. The provider speaks a short sentence once in the chosen voice,
 * with the user's own key and its fast conversation model (the voices sound the same in every model),
 * so the sample is the voice Talova will answer in; the watch keeps it in its cache, so hearing it
 * again is instant and costs nothing.
 */
class VoiceSamples(private val context: Context) {

    class Sample(val pcm: ByteArray, val sampleRate: Int)

    /** No sample now: no key, no connection, or the provider refused. */
    class Unavailable(message: String) : Exception(message)

    /**
     * [voice] saying [sentence]: from the cache, or spoken by the provider now (a few seconds of audio,
     * well under a cent, counted in the statistics).
     */
    suspend fun get(provider: Provider, voice: String, sentence: String): Sample {
        // The sentence is in the app's language; another language or wording gets a sample of its own.
        val name = "${provider.id.key}-$voice-${sentence.hashCode().toUInt().toString(16)}.pcm"
        val file = File(File(context.cacheDir, DIR), name)
        withContext(Dispatchers.IO) { runCatching { read(file) }.getOrNull() }?.let { return it }
        val sample = speak(provider, voice, sentence)
        withContext(Dispatchers.IO) { runCatching { write(file, sample) } }
        return sample
    }

    private suspend fun speak(provider: Provider, voice: String, sentence: String): Sample {
        val app = context.talova
        val key = app.keys.get(provider.id) ?: throw Unavailable("No key for ${provider.displayName}.")
        val model = provider.defaultModel
        val config = SessionConfig(
            apiKey = key,
            model = model.id,
            voice = voice,
            instructions = INSTRUCTIONS,
            tools = emptyList(),
            webSearch = false,
            thinkingLevel = model.thinkingLevel,
        )
        val session = withContext(Dispatchers.Default) { Sessions.create(provider.id, config, app.http) }
        val audio = ByteArrayOutputStream()
        val said = StringBuilder()
        val cost = CostMeter(model.pricing)
        try {
            var asked = false
            val end = withTimeoutOrNull(TIMEOUT_MS) {
                session.connect()
                session.events.first { event ->
                    when (event) {
                        // Ready comes again after a reconnect; the sentence is asked for once.
                        VoiceEvent.Ready -> if (!asked) {
                            asked = true
                            session.sendText(sentence)
                        }
                        is VoiceEvent.AssistantAudio -> audio.write(event.pcm)
                        is VoiceEvent.AssistantTranscript -> said.append(event.delta)
                        is VoiceEvent.Usage -> cost.addTokens(event.usage)
                        else -> Unit
                    }
                    event is VoiceEvent.TurnComplete || event is VoiceEvent.Closed
                }
            }
            (end as? VoiceEvent.Closed)?.error?.let { throw Unavailable(it.message) }
            if (audio.size() == 0) throw Unavailable(if (end == null) "No answer in time." else "The answer had no audio.")
            // Whether the model read the sentence and nothing else. Gemini's transcript of so short an
            // answer comes after its end, so the diagnostics wait a moment for it.
            if (Diagnostics.enabled) {
                withTimeoutOrNull(TRANSCRIPT_WAIT_MS) {
                    session.events.collect { if (it is VoiceEvent.AssistantTranscript) said.append(it.delta) }
                }
                Log.d("TalovaEvent", "voice sample $voice, ${Pcm.durationMs(audio.size(), session.outputSampleRate)} ms: $said")
            }
            return Sample(audio.toByteArray(), session.outputSampleRate)
        } finally {
            session.close()
            // Whatever was spoken is paid for, also when the user tapped another voice in between.
            cost.addOutputAudio(Pcm.durationMs(audio.size(), session.outputSampleRate))
            if (cost.usd > 0) withContext(NonCancellable) { app.usage.record(cost.usd, talkMs = 0, conversations = 0) }
        }
    }

    /**
     * Plays [sample] at Talova's own volume ([savedVolume], see SpeechVolume) and returns once it was
     * heard; cancelling stops it at once.
     */
    suspend fun play(sample: Sample, savedVolume: Int) {
        val volume = SpeechVolume(context)
        val player = SpeechPlayer(sample.sampleRate)
        volume.begin(savedVolume)
        try {
            player.enqueue(TURN, sample.pcm)
            // The play head can stall at the very end; never wait much longer than the sample lasts.
            val until = System.currentTimeMillis() + Pcm.durationMs(sample.pcm.size, sample.sampleRate) + 1_500
            while (player.isPlaying && System.currentTimeMillis() < until) delay(50)
        } finally {
            player.release()
            volume.end()
        }
    }

    private fun read(file: File): Sample? {
        if (!file.exists()) return null
        DataInputStream(file.inputStream().buffered()).use { input ->
            val rate = input.readInt()
            return Sample(input.readBytes(), rate)
        }
    }

    private fun write(file: File, sample: Sample) {
        file.parentFile?.mkdirs()
        DataOutputStream(file.outputStream().buffered()).use { out ->
            out.writeInt(sample.sampleRate)
            out.write(sample.pcm)
        }
    }

    private companion object {
        const val DIR = "voice-samples"
        const val TURN = "sample"
        const val TIMEOUT_MS = 20_000L
        const val TRANSCRIPT_WAIT_MS = 1_500L
        const val INSTRUCTIONS =
            "You record a voice sample for a voice picker. The user sends one short text. Read exactly that text " +
                "aloud, naturally and warmly, in the language it is written in. Say nothing before or after it: " +
                "no greeting, no reply, no question."
    }
}
