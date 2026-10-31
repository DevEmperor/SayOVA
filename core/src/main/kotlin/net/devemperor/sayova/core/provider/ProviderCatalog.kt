package net.devemperor.sayova.core.provider

/**
 * The providers Sayova can talk to, with their models, voices and prices.
 *
 * Model ids, voices and prices are facts read from the providers' own pages, never guesses; the date
 * they were read is next to each. Prices are in US dollars.
 */
enum class ProviderId(val key: String) {
    GEMINI("gemini"),
    OPENAI("openai");

    companion object {
        fun fromKey(key: String?): ProviderId? = entries.firstOrNull { it.key == key }
    }
}

data class Provider(
    val id: ProviderId,
    val displayName: String,
    val models: List<Model>,
    val voices: List<Voice>,
    val defaultVoice: String,
    /** Where a user creates a key. */
    val keyUrl: String,
    /** How keys of this provider start, to catch a key pasted for the wrong provider. */
    val keyPrefixes: List<String>,
) {
    val defaultModel: Model get() = models.first()
    /** The cheaper one: each provider's fast model is listed first. */
    val economyModel: Model get() = models.first()
    fun model(id: String?): Model = models.firstOrNull { it.id == id } ?: defaultModel
    fun voice(id: String?): Voice = voices.firstOrNull { it.id == id } ?: voices.first { it.id == defaultVoice }
}

data class Model(
    val id: String,
    /** Short label for the watch ("Fast", "Thorough"). */
    val label: String,
    val description: String,
    val pricing: Pricing,
    /** Sent with every session for models that refuse to start without one. */
    val thinkingLevel: String? = null,
)

data class Voice(val id: String, val description: String)

/**
 * Per-minute prices are used where the provider publishes them (Gemini), token prices otherwise
 * (OpenAI reports exact token counts per answer).
 */
data class Pricing(
    val inputAudioPerMinute: Double = 0.0,
    val outputAudioPerMinute: Double = 0.0,
    val inputAudioPerMTok: Double = 0.0,
    val cachedInputPerMTok: Double = 0.0,
    val outputAudioPerMTok: Double = 0.0,
    val inputTextPerMTok: Double = 0.0,
    val outputTextPerMTok: Double = 0.0,
)

object ProviderCatalog {

    // Read 2026-10-27 from ai.google.dev/gemini-api/docs/pricing and …/live-api/capabilities:
    // gemini-3.8-live is the recommended default, gemini-3.8-live-extended-thinking adds background
    // reasoning; both cost $0.005/min audio in and $0.018/min audio out ($3 / $12 per 1M tokens).
    private val geminiPricing = Pricing(
        inputAudioPerMinute = 0.005,
        outputAudioPerMinute = 0.018,
        inputTextPerMTok = 0.75,
        outputTextPerMTok = 4.50,
    )

    // The 30 prebuilt voices, read 2026-10-27 from ai.google.dev/gemini-api/docs/speech-generation.
    private val geminiVoices = listOf(
        Voice("Kore", "Firm"), Voice("Puck", "Upbeat"), Voice("Charon", "Informative"),
        Voice("Zephyr", "Bright"), Voice("Fenrir", "Excitable"), Voice("Leda", "Youthful"),
        Voice("Orus", "Firm"), Voice("Aoede", "Breezy"), Voice("Callirrhoe", "Easy-going"),
        Voice("Autonoe", "Bright"), Voice("Enceladus", "Breathy"), Voice("Iapetus", "Clear"),
        Voice("Umbriel", "Easy-going"), Voice("Algieba", "Smooth"), Voice("Despina", "Smooth"),
        Voice("Erinome", "Clear"), Voice("Algenib", "Gravelly"), Voice("Rasalgethi", "Informative"),
        Voice("Laomedeia", "Upbeat"), Voice("Achernar", "Soft"), Voice("Alnilam", "Firm"),
        Voice("Schedar", "Even"), Voice("Gacrux", "Mature"), Voice("Pulcherrima", "Forward"),
        Voice("Achird", "Friendly"), Voice("Zubenelgenubi", "Casual"), Voice("Vindemiatrix", "Gentle"),
        Voice("Sadachbia", "Lively"), Voice("Sadaltager", "Knowledgeable"), Voice("Sulafat", "Warm"),
    )

    val gemini = Provider(
        id = ProviderId.GEMINI,
        displayName = "Google Gemini",
        models = listOf(
            Model("gemini-3.8-live", "Fast", "Quick, natural answers", geminiPricing),
            // Refuses a session without a thinking level ("low", "medium" or "high"; read 2026-10-29 from
            // …/live-api/capabilities); high is what the user picks this model for.
            Model("gemini-3.8-live-extended-thinking", "Thorough", "Thinks longer before answering", geminiPricing, thinkingLevel = "high"),
        ),
        voices = geminiVoices,
        defaultVoice = "Kore",
        keyUrl = "https://aistudio.google.com/apikey",
        // AI Studio now creates "auth keys" whose format the docs do not state, so no prefix check.
        keyPrefixes = emptyList(),
    )

    // Read 2026-10-27 from developers.openai.com/api/docs/pricing (per 1M tokens): gpt-realtime-2.1
    // audio $32 in / $0.40 cached / $64 out, text $4 / $24; gpt-realtime-2.1-mini audio $10 / $0.30 /
    // $20, text $0.60 / $2.40. Voices from …/guides/realtime-conversations (marin and cedar are the
    // ones OpenAI recommends for quality).
    val openAi = Provider(
        id = ProviderId.OPENAI,
        displayName = "OpenAI",
        models = listOf(
            Model(
                "gpt-realtime-2.1-mini", "Fast", "Quick and affordable",
                Pricing(inputAudioPerMTok = 10.0, cachedInputPerMTok = 0.30, outputAudioPerMTok = 20.0, inputTextPerMTok = 0.60, outputTextPerMTok = 2.40),
            ),
            Model(
                "gpt-realtime-2.1", "Best", "OpenAI's strongest voice model",
                Pricing(inputAudioPerMTok = 32.0, cachedInputPerMTok = 0.40, outputAudioPerMTok = 64.0, inputTextPerMTok = 4.0, outputTextPerMTok = 24.0),
            ),
        ),
        // OpenAI describes none of its voices beyond recommending two, so the others carry no label.
        voices = listOf(
            Voice("marin", "Recommended"), Voice("cedar", "Recommended"), Voice("alloy", ""),
            Voice("ash", ""), Voice("ballad", ""), Voice("coral", ""),
            Voice("echo", ""), Voice("sage", ""), Voice("shimmer", ""), Voice("verse", ""),
        ),
        defaultVoice = "marin",
        keyUrl = "https://platform.openai.com/api-keys",
        keyPrefixes = listOf("sk-"),
    )

    val all = listOf(gemini, openAi)

    fun get(id: ProviderId): Provider = when (id) {
        ProviderId.GEMINI -> gemini
        ProviderId.OPENAI -> openAi
    }
}
