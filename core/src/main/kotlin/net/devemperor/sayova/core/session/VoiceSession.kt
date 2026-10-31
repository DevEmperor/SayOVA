package net.devemperor.sayova.core.session

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import net.devemperor.sayova.core.tools.ToolSpec

/**
 * One live conversation with a speech-to-speech model. Each provider speaks its own protocol; an
 * adapter translates it into [VoiceEvent]s, so the rest of the app never sees a provider's wire format.
 *
 * Threading: every method may be called from any thread. Events arrive on [events] in the order the
 * server sent them.
 */
interface VoiceSession {
    val events: Flow<VoiceEvent>

    /** Sample rate [sendAudio] expects (mono, 16-bit little-endian PCM). */
    val inputSampleRate: Int

    /** Sample rate of [VoiceEvent.AssistantAudio] (mono, 16-bit little-endian PCM). */
    val outputSampleRate: Int

    fun connect()

    /** Streams microphone audio. Audio sent before [VoiceEvent.Ready] is held back and replayed. */
    fun sendAudio(pcm: ByteArray)

    /** The user stopped talking on purpose (push-to-talk released), so the model need not wait for silence. */
    fun endOfUserAudio()

    /** A typed message instead of speech. */
    fun sendText(text: String)

    fun sendToolResult(callId: String, name: String, result: JsonObject)

    /**
     * The user cut the answer short. [turnKey] is the assistant turn being played and [playedMs] how
     * much of it was actually heard, so the model's memory of the conversation matches what was said.
     */
    fun interrupt(turnKey: String?, playedMs: Long)

    fun close()
}

data class SessionConfig(
    val apiKey: String,
    val model: String,
    val voice: String,
    val instructions: String,
    val tools: List<ToolSpec>,
    /** Let the model search the web (Gemini: built-in Google Search; OpenAI: the search_web tool). */
    val webSearch: Boolean = true,
    /** Pause that ends the user's turn. 500–800 ms is what the providers recommend. */
    val silenceMs: Int = 700,
    /** How long a model that requires it thinks before answering (Gemini: low, medium, high). */
    val thinkingLevel: String? = null,
)

sealed interface VoiceEvent {
    /** The server accepted the configuration; audio may flow from now on. */
    data object Ready : VoiceEvent

    /** The connection dropped and is being re-established; the conversation continues afterwards. */
    data object Reconnecting : VoiceEvent

    /** The server noticed the user started speaking. */
    data class SpeechStarted(val turnKey: String?) : VoiceEvent

    /** The server noticed the user stopped speaking; an answer is on its way. */
    data object SpeechStopped : VoiceEvent

    /**
     * Text of what the user said. [replace] means [text] is the whole turn so far, otherwise it is a
     * piece to append. Transcripts can arrive after the answer has started (OpenAI transcribes in
     * parallel), which is why they carry the key of the turn they belong to.
     */
    data class UserTranscript(val turnKey: String, val text: String, val replace: Boolean) : VoiceEvent

    /** A piece of what the assistant says (or writes, in text-only mode). */
    data class AssistantTranscript(val turnKey: String, val delta: String) : VoiceEvent

    /** A chunk of the spoken answer at [VoiceSession.outputSampleRate]. */
    data class AssistantAudio(val turnKey: String, val pcm: ByteArray) : VoiceEvent {
        override fun equals(other: Any?) = other is AssistantAudio && other.turnKey == turnKey && other.pcm.contentEquals(pcm)
        override fun hashCode() = 31 * turnKey.hashCode() + pcm.contentHashCode()
    }

    /** The server cut its own answer short because the user started talking. */
    data object Interrupted : VoiceEvent

    /** The model finished its answer (the audio may still be playing). */
    data object TurnComplete : VoiceEvent

    /**
     * With a turn's end: whether the model still reasons in the background and may say more without
     * being asked (Gemini's extended-thinking model answers at once and keeps thinking).
     */
    data class Reasoning(val active: Boolean) : VoiceEvent

    data class ToolCall(val callId: String, val name: String, val arguments: JsonObject) : VoiceEvent

    data class ToolCallsCancelled(val callIds: List<String>) : VoiceEvent

    /** Token counts of one answer, where the provider reports them. */
    data class Usage(val usage: TokenUsage) : VoiceEvent

    /** The session ended for good. [error] is null when it was closed on purpose. */
    data class Closed(val error: SessionError?) : VoiceEvent

    /** A problem the conversation survives (a rejected tool result, a rate-limited answer, …). */
    data class Warning(val message: String) : VoiceEvent
}

data class TokenUsage(
    val inputTextTokens: Long = 0,
    val inputAudioTokens: Long = 0,
    val cachedInputTokens: Long = 0,
    val outputTextTokens: Long = 0,
    val outputAudioTokens: Long = 0,
) {
    operator fun plus(other: TokenUsage) = TokenUsage(
        inputTextTokens + other.inputTextTokens,
        inputAudioTokens + other.inputAudioTokens,
        cachedInputTokens + other.cachedInputTokens,
        outputTextTokens + other.outputTextTokens,
        outputAudioTokens + other.outputAudioTokens,
    )
}

enum class ErrorKind {
    /** Key missing, wrong or revoked. */
    AUTH,

    /** No credit left, billing not set up, or a spend cap was reached. */
    QUOTA,

    /** Too many requests right now. */
    RATE_LIMIT,

    /** No connection, or it broke and could not be resumed. */
    NETWORK,

    /** The provider refused the request (model unavailable, invalid configuration, …). */
    REJECTED,

    /** The provider ended the session (time limit, server error). */
    SERVER,
}

data class SessionError(val kind: ErrorKind, val message: String)
