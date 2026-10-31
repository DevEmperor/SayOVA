package net.devemperor.talova.demo

import android.content.Context
import android.content.res.Configuration
import android.util.Log
import net.devemperor.talova.R
import net.devemperor.talova.audio.VoiceSamples
import net.devemperor.talova.core.provider.ProviderCatalog
import net.devemperor.talova.ui.settings.AppLanguages
import java.io.File
import java.util.Locale

/**
 * Records the voices of SampleConversation, for debug builds only (see Diagnostics):
 * `adb shell am start -n net.devemperor.talova.debug/net.devemperor.talova.MainActivity --ez record_sample true`.
 * In every app language Gemini's default voice reads the sample's answers, and another voice the user's
 * questions, with the key on this watch, the way a voice sample is made (about 0.2 ¢ each; what was
 * recorded before comes from the voice samples' cache). The audio lands in
 * files/sample-voices/<language>-a<n>.pcm and -q<n>.pcm (16-bit mono, 24 kHz), and from there, as Opus,
 * in assets/sample/<language>/; the log shows what the voice said, to compare with the text.
 */
object SampleVoices {

    suspend fun record(context: Context) {
        val dir = File(context.filesDir, "sample-voices").apply { mkdirs() }
        val samples = VoiceSamples(context)
        val gemini = ProviderCatalog.gemini
        val lines = listOf(
            "q" to listOf(R.string.sample_q1, R.string.sample_q2, R.string.sample_q3),
            "a" to listOf(R.string.sample_a1, R.string.sample_a2, R.string.sample_a3),
        )
        for (tag in AppLanguages.TAGS) {
            val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
            val strings = context.createConfigurationContext(config)
            for ((kind, texts) in lines) {
                val voice = if (kind == "q") USER_VOICE else gemini.defaultVoice
                texts.forEachIndexed { index, res ->
                    val name = "$tag-$kind${index + 1}"
                    val text = strings.getString(res)
                    runCatching { samples.get(gemini, voice, text) }
                        .onSuccess { sample ->
                            File(dir, "$name.pcm").writeBytes(sample.pcm)
                            Log.d(TAG, "sample voice $name: ${sample.sampleRate} Hz, ${sample.pcm.size} bytes, text: $text")
                        }
                        .onFailure { Log.w(TAG, "sample voice $name failed: ${it.message}") }
                }
            }
        }
        Log.d(TAG, "sample voices done")
    }

    /** The user's side: a voice clearly not Talova's. */
    private const val USER_VOICE = "Puck"
    private const val TAG = "TalovaEvent"
}
