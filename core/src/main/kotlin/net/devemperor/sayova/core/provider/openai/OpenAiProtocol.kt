package net.devemperor.sayova.core.provider.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import net.devemperor.sayova.core.session.SessionConfig
import net.devemperor.sayova.core.session.SessionErrors
import net.devemperor.sayova.core.session.TokenUsage
import net.devemperor.sayova.core.session.VoiceEvent
import net.devemperor.sayova.core.tools.SayovaTools
import java.util.Base64

/**
 * Wire format of the OpenAI Realtime API (GA interface) for speech-to-speech conversations, as
 * documented on developers.openai.com/api/docs/guides/realtime-conversations (read 2026-10-27).
 */
object OpenAiProtocol {
    const val RATE = 24_000

    /**
     * Streaming transcription of the user's speech. gpt-live-transcribe is the streaming model of the
     * current gpt-transcribe generation (Dictate's provider notes, read 2026-10-16).
     */
    const val TRANSCRIPTION_MODEL = "gpt-live-transcribe"

    fun url(model: String) = "wss://api.openai.com/v1/realtime?model=$model"

    fun sessionUpdate(config: SessionConfig): String = buildJsonObject {
        put("type", "session.update")
        putJsonObject("session") {
            put("type", "realtime")
            put("model", config.model)
            putJsonArray("output_modalities") { add(JsonPrimitive("audio")) }
            put("instructions", config.instructions)
            putJsonObject("audio") {
                putJsonObject("input") {
                    putJsonObject("format") {
                        put("type", "audio/pcm")
                        put("rate", RATE)
                    }
                    putJsonObject("transcription") { put("model", TRANSCRIPTION_MODEL) }
                    putJsonObject("turn_detection") {
                        // Semantic VAD waits for a finished thought rather than a fixed pause, which
                        // suits people thinking aloud; it answers and lets itself be interrupted.
                        put("type", "semantic_vad")
                        put("create_response", true)
                        put("interrupt_response", true)
                    }
                }
                putJsonObject("output") {
                    putJsonObject("format") {
                        put("type", "audio/pcm")
                        put("rate", RATE)
                    }
                    put("voice", config.voice)
                }
            }
            val tools = if (config.webSearch) config.tools + SayovaTools.searchWeb else config.tools
            putJsonArray("tools") {
                tools.forEach { tool ->
                    addJsonObject {
                        put("type", "function")
                        put("name", tool.name)
                        put("description", tool.description)
                        put("parameters", tool.openAiSchema())
                    }
                }
            }
            put("tool_choice", "auto")
        }
    }.toString()

    fun appendAudio(pcm: ByteArray): String = buildJsonObject {
        put("type", "input_audio_buffer.append")
        put("audio", Base64.getEncoder().encodeToString(pcm))
    }.toString()

    fun userText(text: String): String = buildJsonObject {
        put("type", "conversation.item.create")
        putJsonObject("item") {
            put("type", "message")
            put("role", "user")
            putJsonArray("content") {
                addJsonObject {
                    put("type", "input_text")
                    put("text", text)
                }
            }
        }
    }.toString()

    fun functionOutput(callId: String, result: JsonObject): String = buildJsonObject {
        put("type", "conversation.item.create")
        putJsonObject("item") {
            put("type", "function_call_output")
            put("call_id", callId)
            put("output", result.toString())
        }
    }.toString()

    fun responseCreate(): String = """{"type":"response.create"}"""

    fun responseCancel(): String = """{"type":"response.cancel"}"""

    fun truncate(itemId: String, audioEndMs: Long): String = buildJsonObject {
        put("type", "conversation.item.truncate")
        put("item_id", itemId)
        put("content_index", 0)
        put("audio_end_ms", audioEndMs)
    }.toString()
}

/** Turns OpenAI Realtime server events into [VoiceEvent]s. Turns are keyed by the server's item ids. */
class OpenAiTranslator {
    private val json = Json { ignoreUnknownKeys = true }

    /** Function calls of the latest answer that still wait for their result. */
    val pendingCalls = mutableSetOf<String>()

    var responseActive = false
        private set

    private var droppedItem: String? = null

    sealed interface Control {
        data object SessionReady : Control
        data class Failure(val code: String?, val message: String) : Control
    }

    fun drop(itemId: String?) {
        droppedItem = itemId
    }

    fun translate(text: String, onControl: (Control) -> Unit = {}): List<VoiceEvent> {
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return emptyList()
        val type = obj.string("type") ?: return emptyList()
        return when (type) {
            "session.updated" -> {
                onControl(Control.SessionReady)
                emptyList()
            }
            "input_audio_buffer.speech_started" -> listOf(VoiceEvent.SpeechStarted(obj.string("item_id")))
            "input_audio_buffer.speech_stopped" -> listOf(VoiceEvent.SpeechStopped)
            "conversation.item.input_audio_transcription.delta" -> {
                val item = obj.string("item_id") ?: return emptyList()
                val delta = obj.string("delta").orEmpty()
                if (delta.isEmpty()) emptyList() else listOf(VoiceEvent.UserTranscript(item, delta, replace = false))
            }
            "conversation.item.input_audio_transcription.completed" -> {
                val item = obj.string("item_id") ?: return emptyList()
                listOf(VoiceEvent.UserTranscript(item, obj.string("transcript").orEmpty().trim(), replace = true))
            }
            "response.created" -> {
                responseActive = true
                emptyList()
            }
            "response.output_audio.delta" -> {
                val item = obj.string("item_id") ?: return emptyList()
                val delta = obj.string("delta") ?: return emptyList()
                if (item == droppedItem) emptyList() else listOf(VoiceEvent.AssistantAudio(item, Base64.getDecoder().decode(delta)))
            }
            "response.output_audio_transcript.delta", "response.output_text.delta" -> {
                val item = obj.string("item_id") ?: return emptyList()
                val delta = obj.string("delta").orEmpty()
                if (item == droppedItem || delta.isEmpty()) emptyList() else listOf(VoiceEvent.AssistantTranscript(item, delta))
            }
            "response.done" -> responseDone(obj["response"] as? JsonObject)
            "error" -> {
                val error = obj["error"] as? JsonObject
                onControl(Control.Failure(error?.string("code"), error?.string("message") ?: "Unknown error"))
                emptyList()
            }
            else -> emptyList()
        }
    }

    private fun responseDone(response: JsonObject?): List<VoiceEvent> {
        responseActive = false
        val events = mutableListOf<VoiceEvent>()
        (response?.get("output") as? JsonArray)?.forEach { element ->
            val item = element as? JsonObject ?: return@forEach
            if (item.string("type") == "function_call") {
                val callId = item.string("call_id") ?: return@forEach
                val name = item.string("name") ?: return@forEach
                val args = item.string("arguments")
                    ?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
                    ?: JsonObject(emptyMap())
                pendingCalls += callId
                events += VoiceEvent.ToolCall(callId, name, args)
            }
        }
        (response?.get("usage") as? JsonObject)?.let { usage -> events += VoiceEvent.Usage(parseUsage(usage)) }
        val status = response?.string("status")
        if (status == "failed") {
            val message = ((response["status_details"] as? JsonObject)?.get("error") as? JsonObject)?.string("message")
            events += VoiceEvent.Warning(message ?: "The answer failed.")
        }
        events += VoiceEvent.TurnComplete
        return events
    }

    private fun parseUsage(usage: JsonObject): TokenUsage {
        val input = usage["input_token_details"] as? JsonObject
        val output = usage["output_token_details"] as? JsonObject
        val cached = input?.long("cached_tokens") ?: 0
        return TokenUsage(
            inputTextTokens = input?.long("text_tokens") ?: 0,
            inputAudioTokens = input?.long("audio_tokens") ?: 0,
            cachedInputTokens = cached,
            outputTextTokens = output?.long("text_tokens") ?: 0,
            outputAudioTokens = output?.long("audio_tokens") ?: 0,
        )
    }

    companion object {
        /** Error codes after which the session cannot go on. */
        fun isFatal(code: String?, message: String): Boolean {
            if (code in setOf("invalid_api_key", "insufficient_quota", "model_not_found", "session_expired", "billing_hard_limit_reached")) {
                return true
            }
            val kind = SessionErrors.fromText(message)?.kind
            return kind == net.devemperor.sayova.core.session.ErrorKind.AUTH || kind == net.devemperor.sayova.core.session.ErrorKind.QUOTA
        }
    }
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
