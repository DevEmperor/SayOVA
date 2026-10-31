package net.devemperor.parley.demo

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.devemperor.parley.Diagnostics
import net.devemperor.parley.R
import net.devemperor.parley.audio.SpeechPlayer
import net.devemperor.parley.audio.SpeechVolume
import net.devemperor.parley.core.conversation.Role
import net.devemperor.parley.session.ConversationUiState
import net.devemperor.parley.session.Phase
import net.devemperor.parley.session.ToolRunner
import net.devemperor.parley.session.UiTurn
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.sin

/**
 * The introduction's sample conversation, for before there is a key: three short exchanges that show
 * what Parley does (remembering, an answer, a timer), in Parley's real voice and on the real
 * conversation screen, marked as an example. Nothing goes to the network, the microphone stays off,
 * and no timer is set, nothing remembered, saved or counted.
 *
 * The words come from the strings, so they are in the app's language, and the voice reads exactly
 * them: recorded once per language with Gemini's default voice (assets/sample/<language>/a1.ogg and so
 * on, made with SampleVoices). Which folder is a string too, so voice and words always match. Played
 * back, a clip lights its answer up word by word like a live one.
 */
class SampleConversation(private val context: Context, private val scope: CoroutineScope, private val savedVolume: Int) {

    /** What the user says, what Parley does about it (the notice and the transcript's line), and its answer. */
    private class Exchange(val question: String, val action: Pair<String, String>?, val answer: String, val clip: String)

    private val voice = "sample/${context.getString(R.string.sample_voice)}"
    private val exchanges = listOf(
        Exchange(
            question = context.getString(R.string.sample_q1),
            action = context.getString(R.string.notice_remembered) to
                context.getString(R.string.note_remembered, context.getString(R.string.sample_fact)),
            answer = context.getString(R.string.sample_a1),
            clip = "$voice/a1.ogg",
        ),
        Exchange(context.getString(R.string.sample_q2), null, context.getString(R.string.sample_a2), "$voice/a2.ogg"),
        Exchange(
            question = context.getString(R.string.sample_q3),
            action = context.getString(R.string.note_timer, ToolRunner.formatDuration(20 * 60)).let { it to it },
            answer = context.getString(R.string.sample_a3),
            clip = "$voice/a3.ogg",
        ),
    )

    private val _state = MutableStateFlow(ConversationUiState(showCost = false, sample = true))
    val state: StateFlow<ConversationUiState> = _state.asStateFlow()

    private val _scriptDone = MutableStateFlow(false)
    /** The exchanges are over; the conversation listens on until the user hangs up. */
    val scriptDone: StateFlow<Boolean> = _scriptDone.asStateFlow()

    private val volume = SpeechVolume(context)
    private var player: SpeechPlayer? = null
    private var script: Job? = null
    private val turns = mutableListOf<UiTurn>()
    private var phase = Phase.CONNECTING
    private var userSpeaking = false
    private var answerKey: String? = null
    private var notice: String? = null
    private var noticeUntil = 0L
    private var readyAt = 0L
    private var soundOff = false
    private var ended = false

    fun start() {
        volume.begin(savedVolume)
        script = scope.launch {
            val clips = withContext(Dispatchers.IO) { exchanges.map { decode(it.clip) } }
            player = SpeechPlayer(clips.first().second).apply { setMuted(soundOff) }
            delay(CONNECT_MS)
            phase = Phase.LISTENING
            readyAt = SystemClock.elapsedRealtime()
            exchanges.forEachIndexed { index, exchange ->
                delay(PAUSE_MS)
                speak("user-$index", exchange.question)
                phase = Phase.THINKING
                delay(THINK_MS)
                exchange.action?.let { (shown, line) ->
                    showNotice(shown, NOTICE_MS)
                    turns += UiTurn("tool-$index", Role.TOOL, line)
                    delay(ACTION_MS)
                }
                answer("parley-$index", exchange.answer, clips[index].first)
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

    /** The user's words appear as they would while spoken: word by word, or a few characters at a time without spaces. */
    private suspend fun speak(key: String, text: String) {
        val pieces = if (' ' in text) text.split(' ').runningReduce { shown, word -> "$shown $word" } else text.indices.map { text.substring(0, it + 1) }
        val step = (text.length * MS_PER_CHAR / pieces.size).coerceAtLeast(40)
        userSpeaking = true
        pieces.forEach { shown ->
            setTurn(UiTurn(key, Role.USER, shown))
            delay(step)
        }
        userSpeaking = false
    }

    private suspend fun answer(key: String, text: String, pcm: ByteArray) {
        answerKey = key
        turns += UiTurn(key, Role.ASSISTANT, text, spokenChars = 0)
        // Tests lay the voice under a screen recording by this (the emulator plays no sound).
        if (Diagnostics.enabled) Log.d("ParleyEvent", "sample answer $key")
        player?.enqueue(key, pcm)
        phase = Phase.SPEAKING
        delay(TICK_MS)
        while (player?.isPlaying == true) delay(TICK_MS)
        delay(SPEAKING_TAIL_MS)
        phase = Phase.LISTENING
    }

    private fun setTurn(turn: UiTurn) {
        val at = turns.indexOfFirst { it.key == turn.key }
        if (at >= 0) turns[at] = turn else turns += turn
    }

    private fun showNotice(text: String, durationMs: Long) {
        notice = text
        noticeUntil = SystemClock.elapsedRealtime() + durationMs
    }

    private fun publish() {
        val now = SystemClock.elapsedRealtime()
        val player = player
        if (notice != null && now > noticeUntil) notice = null
        val shown = turns.map { turn ->
            if (turn.key != answerKey || player == null || phase != Phase.SPEAKING) return@map turn.copy(spokenChars = turn.text.length)
            val received = player.receivedMs(turn.key)
            val heard = if (received <= 0) 0 else (turn.text.length * player.playedMs(turn.key).toDouble() / received).toInt()
            turn.copy(spokenChars = heard.coerceIn(0, turn.text.length))
        }
        _state.value = _state.value.copy(
            phase = phase,
            turns = shown,
            // Something like a voice for the orb to listen to while the user's words appear.
            micLevel = if (userSpeaking) 0.5f + 0.3f * abs(sin(now / 110.0)).toFloat() else 0f,
            speechLevel = player?.level ?: 0f,
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
        const val CONNECT_MS = 700L
        /** Before each of the user's questions. */
        const val PAUSE_MS = 900L
        /** About the pace of speech, read as text. */
        const val MS_PER_CHAR = 60L
        const val THINK_MS = 700L
        const val ACTION_MS = 600L
        const val NOTICE_MS = 2_500L
        const val SPEAKING_TAIL_MS = 400L
        const val CODEC_WAIT_US = 10_000L
    }
}
