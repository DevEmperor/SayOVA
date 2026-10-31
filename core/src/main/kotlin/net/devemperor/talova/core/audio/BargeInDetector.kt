package net.devemperor.talova.core.audio

/**
 * Decides, while Talova answers over the watch speaker, whether the microphone hears the user talking
 * over the answer or only the answer itself.
 *
 * The speaker sits a few centimetres from the microphone, so an open microphone always hears Talova;
 * handed to the provider, that echo reads as the user interrupting. Instead the microphone audio stays
 * on the watch during an answer and this detector compares its loudness with what was just played.
 *
 * The caller passes, as the playback level, the loudest part of the answer played within the echo's
 * possible delay (speaker and microphone buffers add a few hundred milliseconds), so the echo can never
 * be louder than that reference plus the coupling: how loud the answer comes back, which the platform's
 * echo canceller and the watch's acoustics set. The detector learns the coupling from the loudest echo
 * in the first second of every answer, then follows it slowly: down, because an estimate that sank
 * towards the average echo would take the next loud syllable for the user, and up only by what the
 * echo itself wanders — a voice starting softly above the echo must not teach it to expect that voice.
 * It reports the user once the microphone is clearly louder than the coupling predicts, for long
 * enough not to be a click.
 *
 * The coupling belongs to the watch, not to one answer, so from the second answer on the first second
 * is no longer blind: users often talk over the very start of an answer, and that voice, far above the
 * coupling already known, used to be learned as echo; every interruption after it then went unheard.
 * Only a change of volume or sound ([relearn]) starts blind again, since the echo canceller may then
 * leave more of the answer.
 *
 * Levels are [Pcm.level] values (0 = -60 dBFS, 1 = 0 dBFS, so 0.1 is 6 dB); one call per microphone
 * frame of about 20 ms. Thread-safe: frames come from the microphone thread, answers start on the main
 * thread.
 */
class BargeInDetector(
    /** How far the microphone must rise above the predicted echo: 0.12 ≈ 7 dB. */
    private val margin: Float = 0.12f,
    /** Below this the microphone hears the room, not a voice (≈ -27 dBFS). */
    private val minLevel: Float = 0.45f,
    /** Consecutive frames above the threshold before it counts (6 × 20 ms). */
    private val holdFrames: Int = 6,
    /** Time at the start of each answer spent learning the coupling, never triggering. */
    private val learnMs: Long = 1_000,
) {
    private var coupling = 0f
    private var learned = false
    /** The coupling was known before the current answer began, so its first second listens too. */
    private var known = false
    private var answerStartedAt = -1L
    private var above = 0

    /** Microphone level minus playback level of the loudest echo; for logs. */
    @get:Synchronized
    val learnedCoupling: Float get() = coupling

    /** The current answer's learning second is over and the coupling known. */
    @Synchronized
    fun settled(nowMs: Long): Boolean = learned && answerStartedAt >= 0 && nowMs - answerStartedAt >= learnMs

    @Synchronized
    fun startAnswer(nowMs: Long) {
        answerStartedAt = nowMs
        above = 0
        known = learned
    }

    /** The volume or the sound changed: the echo is learned anew, blind for a second. */
    @Synchronized
    fun relearn(nowMs: Long) {
        startAnswer(nowMs)
        known = false
    }

    /** @return true when the user talks over the answer; the caller then interrupts it. */
    @Synchronized
    fun onFrame(micLevel: Float, playbackLevel: Float, nowMs: Long): Boolean {
        if (answerStartedAt < 0) startAnswer(nowMs)
        val audible = playbackLevel > AUDIBLE
        val measured = micLevel - playbackLevel
        if (nowMs - answerStartedAt < learnMs) {
            // The echo may not have arrived in the first frames; the loudest one sets the coupling.
            // Known from earlier answers, it takes only what could be echo: a frame far above it is
            // the user, and is judged as such below.
            if (audible && (!known || measured <= coupling + margin)) {
                coupling = (if (learned) maxOf(coupling, measured) else measured).coerceIn(MIN_COUPLING, MAX_COUPLING)
                learned = true
            }
            if (!known) return false
        }
        val expected = if (audible) playbackLevel + coupling else 0f
        val excess = micLevel - expected
        if (micLevel >= minLevel && excess > margin) {
            above++
            if (above >= holdFrames) {
                above = 0
                return true
            }
        } else {
            above = 0
            if (audible) {
                val louder = measured - coupling
                coupling = when {
                    louder > margin * WANDER -> coupling // more than the echo wanders: maybe the user
                    louder > 0f -> coupling + louder * RISE
                    else -> coupling - DECAY_PER_FRAME
                }.coerceIn(MIN_COUPLING, MAX_COUPLING)
            }
        }
        return false
    }

    private companion object {
        const val AUDIBLE = 0.15f
        const val MIN_COUPLING = -0.6f
        const val MAX_COUPLING = 0.1f
        /** Share of the margin by which the echo itself rises above the learned peak (≈ 2 dB). */
        const val WANDER = 0.25f
        /** Share of a slightly louder echo taken over per frame. */
        const val RISE = 0.1f
        /** About 1 dB per second at 50 frames a second. */
        const val DECAY_PER_FRAME = 0.0003f
    }
}
