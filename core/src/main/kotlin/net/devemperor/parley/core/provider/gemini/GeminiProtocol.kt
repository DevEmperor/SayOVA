package net.devemperor.parley.core.provider.gemini

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import net.devemperor.parley.core.session.SessionConfig
import net.devemperor.parley.core.session.TokenUsage
import net.devemperor.parley.core.session.VoiceEvent
import java.util.Base64

/**
 * Wire format of the Gemini Live API (BidiGenerateContent over a WebSocket), as documented on
 * ai.google.dev/api/live and the Live API guides under …/docs/live-api (read 2026-10-27). Pure functions, so every message
 * shape is covered by unit tests without a network.
 */
object GeminiProtocol {
    const val URL = "wss://generativelanguage.googleapis.com/ws/" +
        "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    const val INPUT_RATE = 16_000
    const val OUTPUT_RATE = 24_000

    fun url(apiKey: String) = "$URL?key=$apiKey"

    fun setup(config: SessionConfig, resumptionHandle: String?): String = buildJsonObject {
        putJsonObject("setup") {
            put("model", "models/${config.model}")
            putJsonObject("generationConfig") {
                // Native-audio models answer in audio only; with the sound off the transcript is the
                // text the user reads.
                putJsonArray("responseModalities") { add(JsonPrimitive("AUDIO")) }
                putJsonObject("speechConfig") {
                    putJsonObject("voiceConfig") {
                        putJsonObject("prebuiltVoiceConfig") { put("voiceName", config.voice) }
                    }
                }
                config.thinkingLevel?.let { level -> putJsonObject("thinkingConfig") { put("thinkingLevel", level) } }
            }
            putJsonObject("systemInstruction") {
                putJsonArray("parts") { addJsonObject { put("text", config.instructions) } }
            }
            putJsonArray("tools") {
                if (config.webSearch) addJsonObject { putJsonObject("googleSearch") {} }
                if (config.tools.isNotEmpty()) {
                    addJsonObject {
                        putJsonArray("functionDeclarations") {
                            config.tools.forEach { tool ->
                                addJsonObject {
                                    put("name", tool.name)
                                    put("description", tool.description)
                                    if (tool.parameters.isNotEmpty()) put("parameters", tool.geminiSchema())
                                }
                            }
                        }
                    }
                }
            }
            putJsonObject("realtimeInputConfig") {
                putJsonObject("automaticActivityDetection") {
                    put("silenceDurationMs", config.silenceMs)
                    put("prefixPaddingMs", 200)
                }
            }
            putJsonObject("inputAudioTranscription") {}
            putJsonObject("outputAudioTranscription") {}
            // An empty object asks for resumption handles; a handle continues an earlier session.
            putJsonObject("sessionResumption") { resumptionHandle?.let { put("handle", it) } }
            // Without compression an audio session ends after 15 minutes.
            putJsonObject("contextWindowCompression") { putJsonObject("slidingWindow") {} }
        }
    }.toString()

    fun audio(pcm: ByteArray): String = buildJsonObject {
        putJsonObject("realtimeInput") {
            putJsonObject("audio") {
                put("data", Base64.getEncoder().encodeToString(pcm))
                put("mimeType", "audio/pcm;rate=$INPUT_RATE")
            }
        }
    }.toString()

    fun audioStreamEnd(): String = """{"realtimeInput":{"audioStreamEnd":true}}"""

    fun text(text: String): String = buildJsonObject {
        putJsonObject("realtimeInput") { put("text", text) }
    }.toString()

    fun toolResponse(callId: String, name: String, result: JsonObject): String = buildJsonObject {
        putJsonObject("toolResponse") {
            putJsonArray("functionResponses") {
                addJsonObject {
                    put("id", callId)
                    put("name", name)
                    put("response", result)
                }
            }
        }
    }.toString()

    /** `goAway.timeLeft` is a protobuf Duration, rendered in JSON as e.g. "9.5s". */
    fun durationMs(value: String?): Long? =
        value?.removeSuffix("s")?.toDoubleOrNull()?.let { (it * 1000).toLong() }
}

/**
 * Turns Gemini server messages into [VoiceEvent]s. Gemini names no turns, so this numbers them: a turn
 * opens with its first transcript or audio and closes with `turnComplete` or `interrupted`.
 */
class GeminiTranslator {
    private val json = Json { ignoreUnknownKeys = true }

    /** Latest handle that lets a dropped connection continue the same conversation. */
    var resumptionHandle: String? = null
        private set

    private var userTurn = 0
    private var modelTurn = 0
    private var userTurnOpen = false
    /** The open user turn has words already; one opened only by the start of speech has not. */
    private var userTurnHasText = false
    private var modelTurnOpen = false
    private var dropping = false

    val currentModelTurnKey: String? get() = if (modelTurnOpen) "m$modelTurn" else null

    /** The user cut the answer short; ignore the rest of it until the model finishes the turn. */
    fun dropCurrentModelTurn() {
        if (modelTurnOpen) dropping = true
    }

    /** Server messages that are not conversation content but steer the connection. */
    sealed interface Control {
        data object SetupComplete : Control
        data class GoAway(val timeLeftMs: Long?) : Control
    }

    fun translate(text: String, onControl: (Control) -> Unit = {}): List<VoiceEvent> {
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return emptyList()
        val events = mutableListOf<VoiceEvent>()

        if ("setupComplete" in obj) onControl(Control.SetupComplete)

        obj["sessionResumptionUpdate"]?.jsonObject?.let { update ->
            val resumable = update["resumable"]?.jsonPrimitive?.booleanOrNull ?: false
            val handle = update["newHandle"]?.jsonPrimitive?.contentOrNull
            if (resumable && !handle.isNullOrBlank()) resumptionHandle = handle
        }

        obj["goAway"]?.jsonObject?.let { goAway ->
            onControl(Control.GoAway(GeminiProtocol.durationMs(goAway["timeLeft"]?.jsonPrimitive?.contentOrNull)))
        }

        obj["voiceActivity"]?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull?.let { type ->
            when (type) {
                "ACTIVITY_START" -> events += VoiceEvent.SpeechStarted(openUserTurn())
                "ACTIVITY_END" -> events += VoiceEvent.SpeechStopped
            }
        }

        obj["serverContent"]?.jsonObject?.let { content -> translateContent(content, events) }

        // The audio is priced by the minute (see CostMeter); the thinking comes on top, billed as text.
        (obj["usageMetadata"] as? JsonObject)?.get("thoughtsTokenCount")?.jsonPrimitive?.longOrNull?.takeIf { it > 0 }?.let { thoughts ->
            events += VoiceEvent.Usage(TokenUsage(outputTextTokens = thoughts))
        }

        obj["toolCall"]?.jsonObject?.get("functionCalls")?.let { calls ->
            (calls as? JsonArray)?.forEach { element ->
                val call = element.jsonObject
                val id = call["id"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val name = call["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val args = call["args"] as? JsonObject ?: JsonObject(emptyMap())
                events += VoiceEvent.ToolCall(id, name, args)
            }
        }

        obj["toolCallCancellation"]?.jsonObject?.get("ids")?.let { ids ->
            val list = ids.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
            if (list.isNotEmpty()) events += VoiceEvent.ToolCallsCancelled(list)
        }

        return events
    }

    private fun translateContent(content: JsonObject, events: MutableList<VoiceEvent>) {
        content["inputTranscription"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull?.let { text ->
            if (text.isNotEmpty()) {
                events += VoiceEvent.UserTranscript(openUserTurn(), text, replace = false)
                userTurnHasText = true
            }
        }

        content["modelTurn"]?.jsonObject?.get("parts")?.let { parts ->
            (parts as? JsonArray)?.forEach { part ->
                val data = part.jsonObject["inlineData"]?.jsonObject?.get("data")?.jsonPrimitive?.contentOrNull
                if (data != null) {
                    val key = openModelTurn()
                    if (!dropping) events += VoiceEvent.AssistantAudio(key, Base64.getDecoder().decode(data))
                }
            }
        }

        content["outputTranscription"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull?.let { text ->
            if (text.isNotEmpty()) {
                val key = openModelTurn()
                if (!dropping) events += VoiceEvent.AssistantTranscript(key, text)
            }
        }

        if (content["interrupted"]?.jsonPrimitive?.booleanOrNull == true) {
            events += VoiceEvent.Interrupted
            // Only the answer ends here; the user who cut it short is still speaking in their turn.
            modelTurnOpen = false
            dropping = false
        }
        if (content["turnComplete"]?.jsonPrimitive?.booleanOrNull == true) {
            events += VoiceEvent.TurnComplete
            closeTurns()
        }
        // Sent with every turnComplete: IN_PROGRESS while background reasoning may still add to the
        // answer, IDLE once all is done (read 2026-10-29 from ai.google.dev/api/live).
        content["interactionStatus"]?.jsonPrimitive?.contentOrNull?.let { status ->
            events += VoiceEvent.Reasoning(active = status == "IN_PROGRESS")
        }
    }

    private fun openUserTurn(): String {
        if (!userTurnOpen) {
            userTurn++
            userTurnOpen = true
            userTurnHasText = false
        }
        return "u$userTurn"
    }

    private fun openModelTurn(): String {
        if (!modelTurnOpen) {
            modelTurn++
            modelTurnOpen = true
            // The answer has begun, so the user's words before it are complete: talking over the
            // answer starts a new user turn rather than continuing the question.
            userTurnOpen = false
        }
        return "m$modelTurn"
    }

    private fun closeTurns() {
        // A user who started speaking over the answer keeps their turn: their words come after it.
        if (userTurnHasText) userTurnOpen = false
        modelTurnOpen = false
        dropping = false
    }
}
