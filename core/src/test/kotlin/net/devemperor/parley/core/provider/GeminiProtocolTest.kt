package net.devemperor.parley.core.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import net.devemperor.parley.core.provider.gemini.GeminiProtocol
import net.devemperor.parley.core.provider.gemini.GeminiTranslator
import net.devemperor.parley.core.session.SessionConfig
import net.devemperor.parley.core.session.VoiceEvent
import net.devemperor.parley.core.tools.ParleyTools
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class GeminiProtocolTest {
    private val config = SessionConfig(
        apiKey = "key",
        model = "gemini-3.8-live",
        voice = "Kore",
        instructions = "Be brief.",
        tools = ParleyTools.standard,
    )

    private fun parse(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `setup carries model, voice, transcription, tools and resumption`() {
        val setup = parse(GeminiProtocol.setup(config, resumptionHandle = "h1"))["setup"]!!.jsonObject
        assertEquals("models/gemini-3.8-live", setup["model"]!!.jsonPrimitive.content)
        val generation = setup["generationConfig"]!!.jsonObject
        assertEquals("AUDIO", generation["responseModalities"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals(
            "Kore",
            generation["speechConfig"]!!.jsonObject["voiceConfig"]!!.jsonObject["prebuiltVoiceConfig"]!!
                .jsonObject["voiceName"]!!.jsonPrimitive.content,
        )
        assertTrue("inputAudioTranscription" in setup)
        assertTrue("outputAudioTranscription" in setup)
        assertEquals("h1", setup["sessionResumption"]!!.jsonObject["handle"]!!.jsonPrimitive.content)
        assertTrue("slidingWindow" in setup["contextWindowCompression"]!!.jsonObject)

        val tools = setup["tools"]!!.jsonArray
        assertTrue(tools.any { "googleSearch" in it.jsonObject })
        val declarations = tools.first { "functionDeclarations" in it.jsonObject }.jsonObject["functionDeclarations"]!!.jsonArray
        val timer = declarations.first { it.jsonObject["name"]!!.jsonPrimitive.content == ParleyTools.SET_TIMER }.jsonObject
        assertEquals("OBJECT", timer["parameters"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("INTEGER", timer["parameters"]!!.jsonObject["properties"]!!.jsonObject["seconds"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        // A tool without parameters must not send an empty schema.
        val status = declarations.first { it.jsonObject["name"]!!.jsonPrimitive.content == ParleyTools.DEVICE_STATUS }.jsonObject
        assertFalse("parameters" in status)
    }

    @Test
    fun `thinking level goes into the generation config only where the model needs one`() {
        fun generation(c: SessionConfig) = parse(GeminiProtocol.setup(c, null))["setup"]!!.jsonObject["generationConfig"]!!.jsonObject
        assertFalse("thinkingConfig" in generation(config))
        val thorough = generation(config.copy(model = "gemini-3.8-live-extended-thinking", thinkingLevel = "high"))
        assertEquals("high", thorough["thinkingConfig"]!!.jsonObject["thinkingLevel"]!!.jsonPrimitive.content)
        assertEquals("high", ProviderCatalog.gemini.model("gemini-3.8-live-extended-thinking").thinkingLevel)
        assertNull(ProviderCatalog.gemini.model("gemini-3.8-live").thinkingLevel)
    }

    @Test
    fun `talking over an answer starts a new user turn`() {
        val t = GeminiTranslator()
        t.translate("""{"voiceActivity": {"type": "ACTIVITY_START"}}""")
        t.translate("""{"serverContent": {"inputTranscription": {"text": "Tell me about Rome."}}}""")
        t.translate("""{"serverContent": {"outputTranscription": {"text": "Rome was founded"}}}""")
        val barge = t.translate("""{"voiceActivity": {"type": "ACTIVITY_START"}}""")
        assertEquals(VoiceEvent.SpeechStarted("u2"), barge.single())
        assertEquals(VoiceEvent.Interrupted, t.translate("""{"serverContent": {"interrupted": true}}""").single())
        t.translate("""{"serverContent": {"turnComplete": true}}""")
        val words = t.translate("""{"serverContent": {"inputTranscription": {"text": "Stop!"}}}""")
        assertEquals("u2", (words.single() as VoiceEvent.UserTranscript).turnKey)
    }

    @Test
    fun `setup without handle still asks for resumption updates`() {
        val setup = parse(GeminiProtocol.setup(config.copy(webSearch = false, tools = emptyList()), null))["setup"]!!.jsonObject
        assertEquals(JsonObject(emptyMap()), setup["sessionResumption"])
        assertEquals(0, (setup["tools"] as JsonArray).size)
    }

    @Test
    fun `audio is base64 pcm at 16 kHz`() {
        val audio = parse(GeminiProtocol.audio(byteArrayOf(1, 2, 3, 4)))["realtimeInput"]!!.jsonObject["audio"]!!.jsonObject
        assertEquals("audio/pcm;rate=16000", audio["mimeType"]!!.jsonPrimitive.content)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), Base64.getDecoder().decode(audio["data"]!!.jsonPrimitive.content))
    }

    @Test
    fun `tool response echoes id and name`() {
        val result = buildJsonObject { put("ok", true) }
        val response = parse(GeminiProtocol.toolResponse("c1", "set_timer", result))["toolResponse"]!!.jsonObject
        val first = response["functionResponses"]!!.jsonArray[0].jsonObject
        assertEquals("c1", first["id"]!!.jsonPrimitive.content)
        assertEquals("set_timer", first["name"]!!.jsonPrimitive.content)
        assertEquals(result, first["response"])
    }

    @Test
    fun `translator numbers turns and maps content`() {
        val t = GeminiTranslator()
        var setupDone = false
        t.translate("""{"setupComplete":{}}""") { if (it == GeminiTranslator.Control.SetupComplete) setupDone = true }
        assertTrue(setupDone)

        val user = t.translate("""{"serverContent":{"inputTranscription":{"text":"Hello"}}}""")
        assertEquals(listOf(VoiceEvent.UserTranscript("u1", "Hello", replace = false)), user)

        val pcm = Base64.getEncoder().encodeToString(byteArrayOf(9, 9))
        val answer = t.translate(
            """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"$pcm"}}]},"outputTranscription":{"text":"Hi"}}}""",
        )
        assertEquals(VoiceEvent.AssistantAudio("m1", byteArrayOf(9, 9)), answer[0])
        assertEquals(VoiceEvent.AssistantTranscript("m1", "Hi"), answer[1])

        assertEquals(listOf(VoiceEvent.TurnComplete), t.translate("""{"serverContent":{"turnComplete":true}}"""))
        assertNull(t.currentModelTurnKey)

        // The next exchange gets new keys.
        assertEquals("u2", (t.translate("""{"serverContent":{"inputTranscription":{"text":"Again"}}}""")[0] as VoiceEvent.UserTranscript).turnKey)
        assertEquals("m2", (t.translate("""{"serverContent":{"outputTranscription":{"text":"Sure"}}}""")[0] as VoiceEvent.AssistantTranscript).turnKey)
    }

    @Test
    fun `dropped answer is suppressed until the turn ends`() {
        val t = GeminiTranslator()
        t.translate("""{"serverContent":{"outputTranscription":{"text":"One"}}}""")
        t.dropCurrentModelTurn()
        assertTrue(t.translate("""{"serverContent":{"outputTranscription":{"text":"Two"}}}""").isEmpty())
        assertEquals(listOf(VoiceEvent.Interrupted), t.translate("""{"serverContent":{"interrupted":true}}"""))
        assertEquals(
            listOf(VoiceEvent.AssistantTranscript("m2", "Three")),
            t.translate("""{"serverContent":{"outputTranscription":{"text":"Three"}}}"""),
        )
    }

    @Test
    fun `resumption handle, goAway and tool calls`() {
        val t = GeminiTranslator()
        t.translate("""{"sessionResumptionUpdate":{"newHandle":"abc","resumable":true}}""")
        assertEquals("abc", t.resumptionHandle)
        t.translate("""{"sessionResumptionUpdate":{"newHandle":"","resumable":false}}""")
        assertEquals("abc", t.resumptionHandle)

        var goAway: Long? = null
        t.translate("""{"goAway":{"timeLeft":"9.5s"}}""") { if (it is GeminiTranslator.Control.GoAway) goAway = it.timeLeftMs }
        assertEquals(9_500L, goAway)

        val call = t.translate("""{"toolCall":{"functionCalls":[{"id":"f1","name":"set_timer","args":{"seconds":60}}]}}""")
        val toolCall = call.single() as VoiceEvent.ToolCall
        assertEquals("f1", toolCall.callId)
        assertEquals("60", toolCall.arguments["seconds"]!!.jsonPrimitive.content)

        assertEquals(
            listOf(VoiceEvent.ToolCallsCancelled(listOf("f1"))),
            t.translate("""{"toolCallCancellation":{"ids":["f1"]}}"""),
        )
    }

    @Test
    fun `garbage is ignored`() {
        assertTrue(GeminiTranslator().translate("not json").isEmpty())
    }
}
