package net.devemperor.sayova.core.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.devemperor.sayova.core.provider.openai.OpenAiProtocol
import net.devemperor.sayova.core.provider.openai.OpenAiTranslator
import net.devemperor.sayova.core.provider.openai.OpenAiWebSearch
import net.devemperor.sayova.core.session.SessionConfig
import net.devemperor.sayova.core.session.TokenUsage
import net.devemperor.sayova.core.session.VoiceEvent
import net.devemperor.sayova.core.tools.SayovaTools
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class OpenAiProtocolTest {
    private val config = SessionConfig(
        apiKey = "sk-test",
        model = "gpt-realtime-2.1-mini",
        voice = "marin",
        instructions = "Be brief.",
        tools = SayovaTools.standard,
    )

    private fun parse(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `session update configures audio, transcription, vad and tools`() {
        val session = parse(OpenAiProtocol.sessionUpdate(config))["session"]!!.jsonObject
        assertEquals("realtime", session["type"]!!.jsonPrimitive.content)
        assertEquals("gpt-realtime-2.1-mini", session["model"]!!.jsonPrimitive.content)
        assertEquals("audio", session["output_modalities"]!!.jsonArray.single().jsonPrimitive.content)
        val input = session["audio"]!!.jsonObject["input"]!!.jsonObject
        assertEquals(24_000, input["format"]!!.jsonObject["rate"]!!.jsonPrimitive.content.toInt())
        assertEquals("semantic_vad", input["turn_detection"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(OpenAiProtocol.TRANSCRIPTION_MODEL, input["transcription"]!!.jsonObject["model"]!!.jsonPrimitive.content)
        assertEquals("marin", session["audio"]!!.jsonObject["output"]!!.jsonObject["voice"]!!.jsonPrimitive.content)

        val names = session["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue(SayovaTools.SEARCH_WEB in names)
        assertTrue(SayovaTools.SET_TIMER in names)
        val timer = session["tools"]!!.jsonArray.first { it.jsonObject["name"]!!.jsonPrimitive.content == SayovaTools.SET_TIMER }.jsonObject
        assertEquals("object", timer["parameters"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `spoken answers and no search`() {
        val session = parse(OpenAiProtocol.sessionUpdate(config.copy(webSearch = false)))["session"]!!.jsonObject
        assertEquals("audio", session["output_modalities"]!!.jsonArray.single().jsonPrimitive.content)
        assertFalse(session["tools"]!!.jsonArray.any { it.jsonObject["name"]!!.jsonPrimitive.content == SayovaTools.SEARCH_WEB })
    }

    @Test
    fun `function output and truncate shapes`() {
        val output = parse(OpenAiProtocol.functionOutput("call_1", buildJsonObject { put("ok", true) }))
        assertEquals("function_call_output", output["item"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("""{"ok":true}""", output["item"]!!.jsonObject["output"]!!.jsonPrimitive.content)
        val truncate = parse(OpenAiProtocol.truncate("item_9", 1500))
        assertEquals("item_9", truncate["item_id"]!!.jsonPrimitive.content)
        assertEquals(1500, truncate["audio_end_ms"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `translator maps a full exchange`() {
        val t = OpenAiTranslator()
        var ready = false
        t.translate("""{"type":"session.updated","session":{}}""") { if (it == OpenAiTranslator.Control.SessionReady) ready = true }
        assertTrue(ready)

        assertEquals(listOf(VoiceEvent.SpeechStarted("item_u")), t.translate("""{"type":"input_audio_buffer.speech_started","item_id":"item_u","audio_start_ms":10}"""))
        assertEquals(listOf(VoiceEvent.SpeechStopped), t.translate("""{"type":"input_audio_buffer.speech_stopped","item_id":"item_u"}"""))
        t.translate("""{"type":"response.created","response":{"id":"r1"}}""")
        assertTrue(t.responseActive)

        val pcm = Base64.getEncoder().encodeToString(byteArrayOf(1, 2))
        assertEquals(
            listOf(VoiceEvent.AssistantAudio("item_a", byteArrayOf(1, 2))),
            t.translate("""{"type":"response.output_audio.delta","response_id":"r1","item_id":"item_a","delta":"$pcm"}"""),
        )
        assertEquals(
            listOf(VoiceEvent.AssistantTranscript("item_a", "Hi")),
            t.translate("""{"type":"response.output_audio_transcript.delta","item_id":"item_a","delta":"Hi"}"""),
        )
        // The user's words arrive after the answer started and still land in their own turn.
        assertEquals(
            listOf(VoiceEvent.UserTranscript("item_u", "Hello there", replace = true)),
            t.translate("""{"type":"conversation.item.input_audio_transcription.completed","item_id":"item_u","transcript":" Hello there "}"""),
        )

        val done = t.translate(
            """{"type":"response.done","response":{"id":"r1","status":"completed","output":[
              {"type":"message","id":"item_a"},
              {"type":"function_call","call_id":"call_7","name":"set_timer","arguments":"{\"seconds\":300}"}],
              "usage":{"input_token_details":{"text_tokens":100,"audio_tokens":50,"cached_tokens":20},
                       "output_token_details":{"text_tokens":10,"audio_tokens":200}}}}""",
        )
        val call = done.filterIsInstance<VoiceEvent.ToolCall>().single()
        assertEquals("call_7", call.callId)
        assertEquals("300", call.arguments["seconds"]!!.jsonPrimitive.content)
        assertEquals(setOf("call_7"), t.pendingCalls)
        assertEquals(TokenUsage(100, 50, 20, 10, 200), done.filterIsInstance<VoiceEvent.Usage>().single().usage)
        assertEquals(VoiceEvent.TurnComplete, done.last())
        assertFalse(t.responseActive)
    }

    @Test
    fun `dropped item is muted`() {
        val t = OpenAiTranslator()
        t.drop("item_a")
        assertTrue(t.translate("""{"type":"response.output_audio_transcript.delta","item_id":"item_a","delta":"x"}""").isEmpty())
        assertEquals(1, t.translate("""{"type":"response.output_audio_transcript.delta","item_id":"item_b","delta":"y"}""").size)
    }

    @Test
    fun `errors are reported as control`() {
        var failure: OpenAiTranslator.Control.Failure? = null
        OpenAiTranslator().translate("""{"type":"error","error":{"type":"invalid_request_error","code":"invalid_api_key","message":"Incorrect API key"}}""") {
            if (it is OpenAiTranslator.Control.Failure) failure = it
        }
        assertEquals("invalid_api_key", failure!!.code)
        assertTrue(OpenAiTranslator.isFatal(failure!!.code, failure!!.message))
        assertFalse(OpenAiTranslator.isFatal("conversation_already_has_active_response", "Conversation already has an active response"))
    }

    @Test
    fun `web search reads output text of the message items`() {
        val search = OpenAiWebSearch(OkHttpClient())
        val text = search.extractText(
            """{"output":[{"type":"web_search_call","id":"ws"},{"type":"message","content":[{"type":"output_text","text":"It is sunny."}]}]}""",
        )
        assertEquals("It is sunny.", text)
    }
}
