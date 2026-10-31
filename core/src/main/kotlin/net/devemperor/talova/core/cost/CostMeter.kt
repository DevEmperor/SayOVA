package net.devemperor.talova.core.cost

import net.devemperor.talova.core.provider.Pricing
import net.devemperor.talova.core.session.TokenUsage

/**
 * Running cost estimate of one conversation in US dollars.
 *
 * Gemini publishes per-minute audio prices, so its cost follows the seconds streamed each way. OpenAI
 * reports exact token counts per answer; until the first report arrives (or if none does) the same
 * duration-based estimate stands in, using OpenAI's rule of thumb of 10 audio tokens per second in and
 * 20 out.
 */
class CostMeter(private val pricing: Pricing) {
    private var inputAudioMs = 0L
    private var outputAudioMs = 0L
    private var tokens = TokenUsage()
    private var tokensReported = false
    private var extraUsd = 0.0

    fun addInputAudio(ms: Long) {
        inputAudioMs += ms
    }

    fun addOutputAudio(ms: Long) {
        outputAudioMs += ms
    }

    fun addTokens(usage: TokenUsage) {
        tokens += usage
        tokensReported = true
    }

    /** Side requests such as a web search. */
    fun addUsd(usd: Double) {
        extraUsd += usd
    }

    val usd: Double
        get() {
            val perMinute = pricing.inputAudioPerMinute * inputAudioMs / 60_000.0 +
                pricing.outputAudioPerMinute * outputAudioMs / 60_000.0
            val perToken = if (tokensReported) {
                tokenCost(tokens)
            } else {
                tokenCost(TokenUsage(inputAudioTokens = inputAudioMs / 100, outputAudioTokens = outputAudioMs / 50))
            }
            return perMinute + perToken + extraUsd
        }

    private fun tokenCost(t: TokenUsage): Double {
        val uncachedAudio = (t.inputAudioTokens - t.cachedInputTokens).coerceAtLeast(0)
        return (uncachedAudio * pricing.inputAudioPerMTok +
            t.cachedInputTokens * pricing.cachedInputPerMTok +
            t.outputAudioTokens * pricing.outputAudioPerMTok +
            t.inputTextTokens * pricing.inputTextPerMTok +
            t.outputTextTokens * pricing.outputTextPerMTok) / 1_000_000.0
    }

    companion object {
        /** "~3 ¢" below a dollar, "~$1.20" above; the tilde because it is an estimate. */
        // A no-break space keeps the figure and its unit on one line on the narrow watch screen.
        fun format(usd: Double): String = when {
            usd < 0.005 -> "<1\u00A0¢"
            usd < 1.0 -> "~${Math.round(usd * 100)}\u00A0¢"
            else -> "~$" + String.format(java.util.Locale.US, "%.2f", usd)
        }
    }
}
