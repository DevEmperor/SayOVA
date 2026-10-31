package net.devemperor.parley.demo

import android.content.Context
import android.content.res.Configuration
import android.util.Log
import net.devemperor.parley.R
import net.devemperor.parley.audio.VoiceSamples
import net.devemperor.parley.core.provider.ProviderCatalog
import net.devemperor.parley.ui.settings.AppLanguages
import java.io.File
import java.util.Locale

/**
 * Records the voice of SampleConversation, for debug builds only (see Diagnostics):
 * `adb shell am start -n net.devemperor.parley.debug/net.devemperor.parley.MainActivity --ez record_sample true`.
 * Gemini's default voice reads each of the sample's answers in every app language, with the key on this
 * watch, the way a voice sample is made (about 0.2 ¢ per answer). The audio lands in
 * files/sample-voices/<language>-<n>.pcm (16-bit mono, 24 kHz), and from there, as Opus, in
 * assets/sample/<language>/a<n>.ogg; the log shows what the voice said, to compare with the text.
 */
object SampleVoices {

    suspend fun record(context: Context) {
        val dir = File(context.filesDir, "sample-voices").apply { mkdirs() }
        val samples = VoiceSamples(context)
        val gemini = ProviderCatalog.gemini
        for (tag in AppLanguages.TAGS) {
            val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
            val strings = context.createConfigurationContext(config)
            listOf(R.string.sample_a1, R.string.sample_a2, R.string.sample_a3).forEachIndexed { index, answer ->
                val text = strings.getString(answer)
                runCatching { samples.get(gemini, gemini.defaultVoice, text) }
                    .onSuccess { sample ->
                        File(dir, "$tag-${index + 1}.pcm").writeBytes(sample.pcm)
                        Log.d(TAG, "sample voice $tag-${index + 1}: ${sample.sampleRate} Hz, ${sample.pcm.size} bytes, text: $text")
                    }
                    .onFailure { Log.w(TAG, "sample voice $tag-${index + 1} failed: ${it.message}") }
            }
        }
        Log.d(TAG, "sample voices done")
    }

    private const val TAG = "ParleyEvent"
}
