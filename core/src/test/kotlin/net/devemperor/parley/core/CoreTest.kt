package net.devemperor.parley.core

import net.devemperor.parley.core.audio.AudioGate
import net.devemperor.parley.core.audio.Pcm
import net.devemperor.parley.core.conversation.Role
import net.devemperor.parley.core.conversation.Turn
import net.devemperor.parley.core.cost.CostMeter
import net.devemperor.parley.core.prompt.AnswerLanguage
import net.devemperor.parley.core.prompt.Instructions
import net.devemperor.parley.core.prompt.Persona
import net.devemperor.parley.core.provider.ProviderCatalog
import net.devemperor.parley.core.session.ErrorKind
import net.devemperor.parley.core.session.SessionErrors
import net.devemperor.parley.core.session.TokenUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class CoreTest {

    @Test
    fun `resampling keeps the duration`() {
        val oneSecond16k = ByteArray(32_000)
        val out = Pcm.resample(oneSecond16k, 16_000, 24_000)
        assertEquals(48_000, out.size)
        assertEquals(1_000L, Pcm.durationMs(out.size, 24_000))
    }

    @Test
    fun `level maps silence and full scale`() {
        assertEquals(0f, Pcm.level(ByteArray(320)))
        val loud = ByteArray(320) { i -> if (i % 2 == 0) 0xFF.toByte() else 0x7F }
        assertTrue(Pcm.level(loud) > 0.95f)
    }

    @Test
    fun `gate replays held audio in order, then passes through`() {
        val gate = AudioGate(maxBufferedBytes = 4)
        val seen = mutableListOf<Byte>()
        gate.send(byteArrayOf(1, 1))
        gate.send(byteArrayOf(2, 2))
        gate.send(byteArrayOf(3, 3)) // pushes the oldest chunk out
        gate.open { seen += it.toList() }
        gate.send(byteArrayOf(4, 4))
        assertEquals(listOf<Byte>(2, 2, 3, 3, 4, 4), seen)
    }

    @Test
    fun `gemini cost follows audio minutes`() {
        val meter = CostMeter(ProviderCatalog.gemini.defaultModel.pricing)
        meter.addInputAudio(10 * 60_000)
        meter.addOutputAudio(4 * 60_000)
        assertEquals(0.05 + 0.072, meter.usd, 1e-9)
    }

    @Test
    fun `openai cost uses reported tokens`() {
        val meter = CostMeter(ProviderCatalog.openAi.defaultModel.pricing)
        meter.addInputAudio(60_000)
        meter.addTokens(TokenUsage(inputAudioTokens = 1_000_000))
        assertEquals(10.0, meter.usd, 1e-9)
    }

    @Test
    fun `cost format`() {
        assertEquals("<1\u00A0¢", CostMeter.format(0.001))
        assertEquals("~12\u00A0¢", CostMeter.format(0.12))
        assertEquals("~$1.25", CostMeter.format(1.25))
    }

    @Test
    fun `errors are classified`() {
        assertEquals(ErrorKind.AUTH, SessionErrors.fromHttp(401, "").kind)
        assertEquals(ErrorKind.QUOTA, SessionErrors.fromHttp(429, "You exceeded your current quota").kind)
        assertEquals(ErrorKind.RATE_LIMIT, SessionErrors.fromHttp(429, "slow down").kind)
        // A rejected Gemini key: the watch shows the provider's sentence, not the JSON around it.
        val gemini = SessionErrors.fromHttp(
            400,
            """{"error": {"code": 400, "message": "API key not valid. Please pass a valid API key.", "status": "INVALID_ARGUMENT"}}""",
        )
        assertEquals(ErrorKind.AUTH, gemini.kind)
        assertEquals("API key not valid. Please pass a valid API key.", gemini.message)
        assertEquals("Incorrect API key provided", SessionErrors.fromHttp(401, """{"error": {"message": "Incorrect API key provided", "type": "invalid_request_error"}}""").message)
        assertEquals(ErrorKind.AUTH, SessionErrors.fromText("API key not valid. Please pass a valid API key.")!!.kind)
        assertEquals(null, SessionErrors.fromText("bye"))
    }

    @Test
    fun `instructions reflect language, persona and mode`() {
        val now = ZonedDateTime.of(2026, 10, 27, 14, 5, 0, 0, ZoneId.of("Europe/Berlin"))
        val spoken = Instructions.build(Persona.TUTOR, "", AnswerLanguage.GERMAN, now, textOnly = false, canSearch = true)
        assertTrue("always reply in German" in spoken)
        assertTrue("Tuesday, 27 October 2026, 14:05" in spoken)
        assertTrue(Persona.TUTOR.prompt in spoken)
        assertTrue("search the web" in spoken)

        val custom = Instructions.build(Persona.CUSTOM, "Talk like a pirate.", AnswerLanguage.AUTO, now, textOnly = true, canSearch = false)
        assertTrue("Talk like a pirate." in custom)
        assertTrue("language the user speaks" in custom)
        assertFalse("search the web" in custom)
        assertFalse("spoken aloud" in custom)
        assertFalse("carries on an earlier" in custom)
    }

    @Test
    fun `a continued conversation brings what was said, newest kept`() {
        val now = ZonedDateTime.of(2026, 10, 28, 12, 0, 0, 0, ZoneId.of("Europe/Berlin"))
        val earlier = listOf(
            Turn(Role.USER, "What is   the capital\nof Peru?", 1),
            Turn(Role.ASSISTANT, "Lima.", 2),
            Turn(Role.NOTE, "Buy pisco", 3),
        )
        val prompt = Instructions.build(Persona.FRIENDLY, "", AnswerLanguage.AUTO, now, textOnly = false, canSearch = false, earlier = earlier)
        assertTrue("carries on an earlier one" in prompt)
        assertTrue("User: What is the capital of Peru?\nParley: Lima.\n(Note saved: Buy pisco)" in prompt)
        // A long history keeps its end: the last turns, not the first.
        val long = (1..400).map { Turn(if (it % 2 == 0) Role.ASSISTANT else Role.USER, "Line number $it of a long talk", it.toLong()) }
        val kept = Instructions.transcriptOf(long)
        assertTrue(kept.length <= 6_000)
        assertTrue(kept.endsWith("Parley: Line number 400 of a long talk"))
        assertFalse("Line number 1 of" in kept)
    }
}
