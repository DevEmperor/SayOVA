package net.devemperor.parley.core

import net.devemperor.parley.core.audio.BargeInDetector
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class BargeInDetectorTest {

    /** Parley's voice: syllables between 0.5 and 0.85 with short pauses, one value per 20 ms frame. */
    private fun playback(frame: Int): Float {
        if (frame < 0) return 0f
        val syllable = (frame / 12) % 5 != 4
        return if (syllable) 0.68f + 0.17f * sin(frame * 0.7f) else 0f
    }

    /** What the engine passes as the playback level: the loudest of the last 600 ms that was played. */
    private fun reference(frame: Int): Float = (frame - 29..frame).maxOf { playback(it) }

    /** The answer coming back into the microphone [delay] frames late, plus room noise. */
    private fun echo(frame: Int, coupling: Float, delay: Int): Float {
        val played = playback(frame - delay)
        return if (played > 0f) maxOf(0.1f, played + coupling) else 0.1f
    }

    @Test
    fun `its own echo never counts as the user`() {
        for (coupling in listOf(-0.4f, -0.15f, -0.02f)) {
            for (delay in listOf(0, 5, 15, 25)) {
                val detector = BargeInDetector()
                detector.startAnswer(0)
                // A minute of answer, long enough for the estimate to drift if it could.
                for (frame in 0 until 3_000) {
                    val heard = detector.onFrame(echo(frame, coupling, delay), reference(frame), frame * 20L)
                    assertFalse("coupling $coupling, delay $delay, frame $frame", heard)
                }
            }
        }
    }

    @Test
    fun `the user talking over the answer is noticed within a sixth of a second`() {
        for (delay in listOf(0, 15)) {
            val detector = BargeInDetector()
            detector.startAnswer(0)
            var noticedAt = -1
            for (frame in 0 until 600) {
                // From frame 400 the user speaks at about -9 dBFS, well above the echo.
                val mic = if (frame >= 400) maxOf(echo(frame, -0.3f, delay), 0.85f) else echo(frame, -0.3f, delay)
                if (detector.onFrame(mic, reference(frame), frame * 20L)) {
                    noticedAt = frame
                    break
                }
            }
            assertTrue("delay $delay: noticed at frame $noticedAt", noticedAt in 400..408)
        }
    }

    @Test
    fun `the user's own voice does not teach it to expect them`() {
        // The answer comes back almost as loud as it is played. From frame 400 the user starts softly,
        // barely above the echo, then from frame 550 speaks up.
        val detector = BargeInDetector()
        detector.startAnswer(0)
        var noticedAt = -1
        var couplingBefore = 0f
        for (frame in 0 until 700) {
            if (frame == 400) couplingBefore = detector.learnedCoupling
            val echo = echo(frame, -0.05f, 15)
            val voice = (frame - 400) % 12 < 8
            val mic = when {
                frame < 400 || !voice -> echo
                frame < 550 -> maxOf(echo, 0.86f)
                else -> maxOf(echo, 0.98f)
            }
            if (frame == 550) {
                assertTrue("coupling rose from $couplingBefore to ${detector.learnedCoupling}", detector.learnedCoupling <= couplingBefore + 0.02f)
            }
            if (detector.onFrame(mic, reference(frame), frame * 20L)) {
                noticedAt = frame
                break
            }
        }
        assertTrue("noticed at frame $noticedAt", noticedAt in 550..580)
    }

    @Test
    fun `nothing triggers while the coupling is being learned`() {
        val detector = BargeInDetector()
        detector.startAnswer(0)
        for (frame in 0 until 49) assertFalse(detector.onFrame(0.95f, 0.5f, frame * 20L))
    }

    @Test
    fun `quiet room noise in a pause is not speech`() {
        val detector = BargeInDetector()
        detector.startAnswer(0)
        for (frame in 0 until 300) assertFalse(detector.onFrame(0.3f, 0f, frame * 20L))
    }
}
