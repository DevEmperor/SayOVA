package net.devemperor.sayova.demo

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.devemperor.sayova.Diagnostics
import net.devemperor.sayova.R
import net.devemperor.sayova.audio.SpeechPlayer
import net.devemperor.sayova.audio.SpeechVolume
import net.devemperor.sayova.core.conversation.Role
import net.devemperor.sayova.session.ConversationUiState
import net.devemperor.sayova.session.Phase
import net.devemperor.sayova.session.ToolRunner
import net.devemperor.sayova.session.UiTurn
import java.io.ByteArrayOutputStream

/**
 * The introduction's sample conversation, for before there is a key: three short exchanges that show
 * what Sayova does (remembering, an answer, a timer), in Sayova's real voice and on the real
 * conversation screen, marked as an example. Nothing goes to the network, the microphone stays off,
 * and no timer is set, nothing remembered, saved or counted.
 *
 * The words come from the strings, so they are in the app's language, and the voices read exactly
 * them, recorded once per language (assets/sample/<language>/, made with SampleVoices): the user's
 * questions in one Gemini voice, so the sample can be followed by ear, Sayova's answers in its default
 * voice. Which folder is a string too, so voices and words always match. The user's words appear as
 * they are spoken, and an answer lights up word by word like a live one.
 */
class SampleConversation(private val context: Context, private val scope: CoroutineScope, private val savedVolume: Int) {

    /** What the user says, what Sayova does about it (the notice and the transcript's line), and its answer. */
    private class Exchange(val question: String, val action: Pair<String, String>?, val answer: String, val number: Int)

    private val voice = "sample/${context.getString(R.string.sample_voice)}"
    private val exchanges = listOf(
        Exchange(
            question = context.getString(R.string.sample_q1),
            action = context.getString(R.string.notice_remembered) to
                context.getString(R.string.note_remembered, context.getString(R.string.sample_fact)),
            answer = context.getString(R.string.sample_a1),
            number = 1,
        ),
        Exchange(context.getString(R.string.sample_q2), null, context.getString(R.string.sample_a2), 2),
        Exchange(
            question = context.getString(R.string.sample_q3),
            action = context.getString(R.string.note_timer, ToolRunner.formatDuration(20 * 60)).let { it to it },
            answer = context.getString(R.string.sample_a3),
            number = 3,
        ),
    )

    // A sample has nothing to connect to: it listens from the first frame.
    private val _state = MutableStateFlow(ConversationUiState(phase = Phase.LISTENING, showCost = false, sample = true))
    val state: StateFlow<ConversationUiState> = _state.asStateFlow()

    private val _scriptDone = MutableStateFlow(false)
    /** The exchanges are over; the conversation listens on until the user hangs up. */
    val scriptDone: StateFlow<Boolean> = _scriptDone.asStateFlow()

    private val volume = SpeechVolume(context)
    private var player: SpeechPlayer? = null
    private var script: Job? = null
    private val turns = mutableListOf<UiTurn>()
    private var phase = Phase.LISTENING
    /** The question being spoken, in full; its turn shows as much of it as has been heard. */
    private var question: UiTurn? = null
    private var answerKey: String? = null
    private var notice: String? = null
    private var noticeUntil = 0L
    private var readyAt = 0L
    private var soundOff = false
    private var ended = false

    fun start() {
        volume.begin(savedVolume)
        readyAt = SystemClock.elapsedRealtime()
        script = scope.launch {
            // Decoded one by one in the order they are needed, the first during the first pause:
            // decoding all six before starting showed "Connecting" on a watch for seconds.
            val clips = exchanges.flatMap { listOf("q${it.number}", "a${it.number}") }
                .associateWith { CompletableDeferred<Pair<ByteArray, Int>>() }
            launch(Dispatchers.IO) { clips.forEach { (name, clip) -> clip.complete(decode("$voice/$name.ogg")) } }
            exchanges.forEachIndexed { index, exchange ->
                delay(PAUSE_MS)
                val (question, rate) = clips.getValue("q${exchange.number}").await()
                // Both voices come as Opus at the same rate (48 kHz), so one player plays them all.
                if (player == null) player = SpeechPlayer(rate).apply { setMuted(soundOff) }
                speak("user-$index", exchange.question, question)
                phase = Phase.THINKING
                delay(THINK_MS)
                exchange.action?.let { (shown, line) ->
                    showNotice(shown, NOTICE_MS)
                    turns += UiTurn("tool-$index", Role.TOOL, line)
                    delay(ACTION_MS)
                }
                answer("sayova-$index", exchange.answer, clips.getValue("a${exchange.number}").await().first)
            }
            _scriptDone.value = true
        }
        scope.launch {
            while (isActive && !ended) {
                publish()
                delay(TICK_MS)
            }
        }
    }

    fun end() {
        if (ended) return
        ended = true
        script?.cancel()
        player?.release()
        volume.end()
        _state.value = _state.value.copy(
            phase = Phase.ENDED,
            turns = turns.map { it.copy(spokenChars = it.text.length) },
            micLevel = 0f,
            speechLevel = 0f,
            notice = null,
        )
    }

    /** Like a conversation's sound button: the answer plays on silently. */
    fun setSoundOff(off: Boolean) {
        if (ended || soundOff == off) return
        soundOff = off
        player?.setMuted(off)
        showNotice(context.getString(if (off) R.string.notice_sound_off else R.string.notice_bezel_volume), 2_500)
    }

    fun stepVolume(direction: Int): Int = volume.step(direction)

    /** The user asks: heard in its own voice, its words appearing as they are said (see [publish]). */
    private suspend fun speak(key: String, text: String, pcm: ByteArray) {
        val turn = UiTurn(key, Role.USER, text)
        question = turn
        turns += turn
        // Tests lay the voices under a screen recording by these lines (the emulator plays no sound).
        if (Diagnostics.enabled) Log.d("SayovaEvent", "sample question $key")
        player?.enqueue(key, pcm)
        playOut()
        question = null
    }

    private suspend fun answer(key: String, text: String, pcm: ByteArray) {
        answerKey = key
        turns += UiTurn(key, Role.ASSISTANT, text, spokenChars = 0)
        if (Diagnostics.enabled) Log.d("SayovaEvent", "sample answer $key")
        player?.enqueue(key, pcm)
        phase = Phase.SPEAKING
        playOut()
        delay(SPEAKING_TAIL_MS)
        phase = Phase.LISTENING
    }

    private suspend fun playOut() {
        delay(TICK_MS)
        while (player?.isPlaying == true) delay(TICK_MS)
    }

    /** As much of [text] as [heard] (0 to 1) covers, the word being said already whole, as live transcripts come. */
    private fun heardPart(text: String, heard: Double): String {
        val chars = (text.length * heard).toInt().coerceIn(0, text.length)
        if (' ' !in text) return text.take(chars)
        return text.take(text.indexOf(' ', chars).takeIf { it >= 0 } ?: text.length)
    }

    private fun showNotice(text: String, durationMs: Long) {
        notice = text
        noticeUntil = SystemClock.elapsedRealtime() + durationMs
    }

    private fun publish() {
        val now = SystemClock.elapsedRealtime()
        val player = player
        if (notice != null && now > noticeUntil) notice = null
        fun heard(key: String): Double {
            val received = player?.receivedMs(key) ?: 0
            return if (player == null || received <= 0) 0.0 else player.playedMs(key).toDouble() / received
        }
        val asking = question
        val shown = turns.mapNotNull { turn ->
            when {
                turn.key == asking?.key -> turn.copy(text = heardPart(turn.text, heard(turn.key))).takeIf { it.text.isNotBlank() }
                turn.key == answerKey && phase == Phase.SPEAKING ->
                    turn.copy(spokenChars = (turn.text.length * heard(turn.key)).toInt().coerceIn(0, turn.text.length))
                else -> turn.copy(spokenChars = turn.text.length)
            }
        }
        // The user's voice moves the orb as the microphone would, Sayova's as its answer.
        val level = player?.level ?: 0f
        _state.value = _state.value.copy(
            phase = phase,
            turns = shown,
            micLevel = if (asking != null) level else 0f,
            speechLevel = if (asking != null) 0f else level,
            elapsedMs = if (readyAt > 0) now - readyAt else 0,
            soundOff = soundOff,
            notice = notice,
        )
    }

    /** A clip as 16-bit mono PCM and its sample rate (48 kHz for Opus). */
    private fun decode(clip: String): Pair<ByteArray, Int> {
        val extractor = MediaExtractor()
        context.assets.openFd(clip).use { fd -> extractor.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length) }
        val format = extractor.getTrackFormat(0)
        extractor.selectTrack(0)
        val codec = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
        codec.configure(format, null, null, 0)
        codec.start()
        val pcm = ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var inputDone = false
        try {
            while (true) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(CODEC_WAIT_US)
                    if (index >= 0) {
                        val size = extractor.readSampleData(requireNotNull(codec.getInputBuffer(index)), 0)
                        if (size < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val index = codec.dequeueOutputBuffer(info, CODEC_WAIT_US)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) rate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                if (index < 0) continue
                val buffer = requireNotNull(codec.getOutputBuffer(index))
                val bytes = ByteArray(info.size)
                buffer.position(info.offset)
                buffer.get(bytes)
                pcm.write(bytes)
                codec.releaseOutputBuffer(index, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        } finally {
            codec.stop()
            codec.release()
            extractor.release()
        }
        return pcm.toByteArray() to rate
    }

    private companion object {
        const val TICK_MS = 66L
        /** Before each of the user's questions. */
        const val PAUSE_MS = 900L
        const val THINK_MS = 700L
        const val ACTION_MS = 600L
        const val NOTICE_MS = 2_500L
        const val SPEAKING_TAIL_MS = 400L
        const val CODEC_WAIT_US = 10_000L
    }
}
