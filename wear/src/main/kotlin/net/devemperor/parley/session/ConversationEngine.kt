package net.devemperor.parley.session

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.devemperor.parley.BuildConfig
import net.devemperor.parley.audio.Haptics
import net.devemperor.parley.audio.Headphones
import net.devemperor.parley.audio.Microphone
import net.devemperor.parley.audio.SpeechPlayer
import net.devemperor.parley.core.audio.Pcm
import net.devemperor.parley.core.conversation.Conversation
import net.devemperor.parley.core.conversation.Role
import net.devemperor.parley.core.conversation.Turn
import net.devemperor.parley.core.cost.CostMeter
import net.devemperor.parley.core.provider.Model
import net.devemperor.parley.core.session.ErrorKind
import net.devemperor.parley.core.session.SessionError
import net.devemperor.parley.core.session.TokenUsage
import net.devemperor.parley.core.session.VoiceEvent
import net.devemperor.parley.core.session.VoiceSession
import net.devemperor.parley.data.BargeIn
import net.devemperor.parley.net.Transport
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs one conversation: microphone in, answer out, transcript and state for the screen.
 *
 * All state lives on the main thread; the microphone and player threads only touch the few volatile
 * flags and counters marked as such. A 15 Hz tick derives what the screen shows (phase, levels,
 * how much of the answer has been heard), so the event handlers stay simple.
 */
class ConversationEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val session: VoiceSession,
    private val model: Model,
    private val providerLabel: String,
    private val providerKey: String,
    private val options: Options,
    private val toolRunner: ToolRunner,
    private val haptics: Haptics,
    private val transport: StateFlow<Transport>,
    private val onSave: suspend (Conversation?, Double) -> Unit,
    private val onFinished: () -> Unit,
) {
    data class Options(
        val textOnly: Boolean,
        val pushToTalk: Boolean,
        val bargeIn: BargeIn,
        val autoEndSeconds: Int,
        val showCost: Boolean,
        val saveHistory: Boolean,
    )

    private class Record(val key: String, val role: Role, val at: Long) {
        val text = StringBuilder()
        var interrupted = false
    }

    private val _state = MutableStateFlow(
        ConversationUiState(
            textOnly = options.textOnly,
            pushToTalk = options.pushToTalk || options.textOnly,
            showCost = options.showCost,
            providerLabel = providerLabel,
        ),
    )
    val state: StateFlow<ConversationUiState> = _state.asStateFlow()

    private val records = mutableListOf<Record>()
    private val conversationId = UUID.randomUUID().toString()
    private val startedAtWall = System.currentTimeMillis()
    private var readyAt = 0L
    private var ready = false
    private var reconnecting = false
    private var finished = false
    private var finalError: SessionError? = null

    private val player: SpeechPlayer? = if (options.textOnly) null else SpeechPlayer(session.outputSampleRate)
    private val microphone = Microphone(session.inputSampleRate, ::onMicAudio, ::onMicLevel)

    // Touched by the microphone thread.
    @Volatile private var micOpen = false
    @Volatile private var micLevel = 0f
    private val inputAudioMs = AtomicLong()
    private val outputAudioMs = AtomicLong()
    private var tokens = TokenUsage()

    private val headphones = Headphones(context)

    private var muted = false
    private var talking = false
    private var currentAnswerKey: String? = null
    private var answerStreaming = false
    /** An answer is being heard: from its first word until it has been played to the end. */
    private var speaking = false
    private var lastAudibleAt = 0L
    private var answerHapticKey: String? = null
    private var awaitingAnswer = false
    private var lastUserTextAt = 0L
    private var lastActivityAt = SystemClock.elapsedRealtime()
    private var pendingTools = 0
    private var endAfterSpeech = false
    private var lastPhase = Phase.CONNECTING
    private var notice: String? = null
    private var noticeUntil = 0L

    fun start() {
        headphones.start()
        scope.launch { session.events.collect(::handle) }
        scope.launch {
            while (isActive && !finished) {
                tick()
                delay(TICK_MS)
            }
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
            !microphone.start()
        ) {
            finish(SessionError(ErrorKind.REJECTED, "The microphone is not available."))
            return
        }
        session.connect()
    }

    // ── Controls ────────────────────────────────────────────────────────────────────────────────

    /**
     * The orb is the one control: while Parley speaks, a tap interrupts; in push-to-talk it opens and
     * closes the microphone; otherwise it mutes and unmutes.
     */
    fun tapOrb() {
        touch()
        when {
            speaking -> interruptAnswer()
            _state.value.pushToTalk -> setTalking(!talking)
            else -> toggleMute()
        }
    }

    fun toggleMute() {
        touch()
        muted = !muted
        if (muted) session.endOfUserAudio()
        tick()
    }

    fun end() {
        if (finished) return
        session.close()
    }

    private fun setTalking(open: Boolean) {
        if (talking == open) return
        talking = open
        if (!open) {
            session.endOfUserAudio()
            awaitingAnswer = true
        }
        tick()
    }

    // ── Microphone ──────────────────────────────────────────────────────────────────────────────

    private fun onMicAudio(pcm: ByteArray) {
        if (!micOpen) return
        session.sendAudio(pcm)
        inputAudioMs.addAndGet(Pcm.durationMs(pcm.size, session.inputSampleRate))
    }

    private fun onMicLevel(level: Float) {
        micLevel = if (micOpen) level else 0f
    }

    // ── Events ──────────────────────────────────────────────────────────────────────────────────

    private fun handle(event: VoiceEvent) {
        if (finished) return
        debugLog(event)
        when (event) {
            VoiceEvent.Ready -> {
                if (!ready) {
                    ready = true
                    readyAt = SystemClock.elapsedRealtime()
                    haptics.listening()
                }
                reconnecting = false
                touch()
            }
            VoiceEvent.Reconnecting -> reconnecting = true
            is VoiceEvent.SpeechStarted -> {
                touch()
                event.turnKey?.let { record(it, Role.USER) }
                // OpenAI reports speech start; the answer must stop here and now (barge-in).
                if (speaking) interruptAnswer()
            }
            VoiceEvent.SpeechStopped -> awaitingAnswer = true
            is VoiceEvent.UserTranscript -> {
                touch()
                val record = record(event.turnKey, Role.USER)
                if (event.replace) record.text.setLength(0)
                record.text.append(event.text)
                lastUserTextAt = SystemClock.elapsedRealtime()
                if (!answerStreaming) awaitingAnswer = true
            }
            is VoiceEvent.AssistantTranscript -> {
                touch()
                startAnswer(event.turnKey)
                record(event.turnKey, Role.ASSISTANT).text.append(event.delta)
            }
            is VoiceEvent.AssistantAudio -> {
                startAnswer(event.turnKey)
                record(event.turnKey, Role.ASSISTANT)
                outputAudioMs.addAndGet(Pcm.durationMs(event.pcm.size, session.outputSampleRate))
                player?.enqueue(event.turnKey, event.pcm)
            }
            VoiceEvent.Interrupted -> interruptAnswer()
            VoiceEvent.TurnComplete -> {
                answerStreaming = false
                awaitingAnswer = false
            }
            is VoiceEvent.ToolCall -> runTool(event)
            is VoiceEvent.ToolCallsCancelled -> Unit
            is VoiceEvent.Usage -> tokens += event.usage
            is VoiceEvent.Warning -> Unit
            is VoiceEvent.Closed -> finish(event.error)
        }
    }

    private fun startAnswer(key: String) {
        currentAnswerKey = key
        answerStreaming = true
        awaitingAnswer = false
    }

    private fun record(key: String, role: Role): Record =
        records.lastOrNull { it.key == key && it.role == role }
            ?: Record(key, role, System.currentTimeMillis()).also { records += it }

    /** Cuts the answer short, keeps only what was heard of it, and tells the provider so. */
    private fun interruptAnswer() {
        val key = currentAnswerKey ?: return
        val record = records.lastOrNull { it.key == key && it.role == Role.ASSISTANT }
        val played = player?.playedMs(key) ?: 0L
        val received = player?.receivedMs(key) ?: 0L
        val wasHeard = speaking
        player?.flush()
        session.interrupt(key, played)
        answerStreaming = false
        speaking = false
        lastAudibleAt = 0
        if (record != null && wasHeard && received > 0 && played < received - 150) {
            val keep = (record.text.length * played / received).toInt()
            val cut = record.text.lastIndexOf(" ", keep).takeIf { it > 0 } ?: keep
            record.text.setLength(cut.coerceIn(0, record.text.length))
            record.text.append(" …")
            record.interrupted = true
        }
    }

    /**
     * Whether the user's voice may cut an answer short right now. Over the watch speaker the
     * microphone hears every answer, which the provider then takes for the user talking over it: the
     * screen flickers between listening and speaking and answers break off. So with the automatic
     * setting the microphone listens during an answer only through headphones; a tap still interrupts.
     */
    private fun voiceBargeIn(): Boolean = when (options.bargeIn) {
        BargeIn.ON -> true
        BargeIn.OFF -> false
        BargeIn.AUTO -> headphones.connected
    }

    private fun runTool(call: VoiceEvent.ToolCall) {
        pendingTools++
        touch()
        if (call.name == net.devemperor.parley.core.tools.ParleyTools.SEARCH_WEB) showNotice("Searching the web…", 8_000)
        scope.launch {
            val outcome = toolRunner.run(call)
            session.sendToolResult(call.callId, call.name, outcome.result)
            pendingTools--
            outcome.savedNote?.let { record("note-${call.callId}", Role.NOTE).text.append(it) }
            outcome.note?.let { note ->
                if (outcome.savedNote == null) record("tool-${call.callId}", Role.TOOL).text.append(note)
                haptics.action()
                showNotice(note, 2_500)
            }
            if (outcome.endsConversation) endAfterSpeech = true
        }
    }

    private fun showNotice(text: String, durationMs: Long) {
        notice = text
        noticeUntil = SystemClock.elapsedRealtime() + durationMs
    }

    private fun touch() {
        lastActivityAt = SystemClock.elapsedRealtime()
    }

    // ── Tick ────────────────────────────────────────────────────────────────────────────────────

    private fun tick() {
        if (finished) return
        val now = SystemClock.elapsedRealtime()
        val playing = player?.isPlaying == true
        if (playing) lastAudibleAt = now

        // Speaking holds from the first word of an answer until the provider has finished it and the
        // last of it has been played, plus a short tail for the room to fall quiet. Audio arrives in
        // bursts over Wi-Fi or Bluetooth, and judging by the playback buffer alone made the state
        // flip back to listening at every gap.
        speaking = if (options.textOnly) answerStreaming else answerStreaming || playing || now - lastAudibleAt < SPEAKING_TAIL_MS

        micOpen = ready && !muted &&
            (!_state.value.pushToTalk || talking) &&
            !(speaking && !voiceBargeIn())
        if (micOpen && micLevel > SPEECH_LEVEL) touch()
        if (BuildConfig.DEBUG) logMicPeak(now)
        if (speaking) touch()

        // Gemini announces no end of speech; a pause after the user's words means an answer is due.
        if (!awaitingAnswer && !answerStreaming && lastUserTextAt > 0 && now - lastUserTextAt > 700 && micLevel < SPEECH_LEVEL) {
            if (records.lastOrNull()?.role == Role.USER) awaitingAnswer = true
        }

        val phase = when {
            reconnecting -> Phase.RECONNECTING
            !ready -> Phase.CONNECTING
            speaking -> Phase.SPEAKING
            pendingTools > 0 -> Phase.THINKING
            awaitingAnswer -> Phase.THINKING
            else -> Phase.LISTENING
        }
        // One tap on the wrist per answer, not one per burst of audio.
        if (phase == Phase.SPEAKING && answerHapticKey != currentAnswerKey) {
            answerHapticKey = currentAnswerKey
            haptics.answering()
        }
        if (phase != lastPhase && BuildConfig.DEBUG) Log.d(TAG, "phase ${lastPhase.name} -> ${phase.name} (mic ${if (micOpen) "open" else "closed"})")
        lastPhase = phase

        if (endAfterSpeech && !speaking && pendingTools == 0) {
            end()
        } else if (options.autoEndSeconds > 0 && phase == Phase.LISTENING && now - lastActivityAt > options.autoEndSeconds * 1000L) {
            end()
        }

        if (notice != null && now > noticeUntil) notice = null

        _state.value = _state.value.copy(
            phase = phase,
            turns = uiTurns(),
            micLevel = if (micOpen) micLevel else 0f,
            speechLevel = player?.level ?: 0f,
            elapsedMs = if (ready) now - readyAt else 0,
            costUsd = cost(),
            transport = transport.value,
            muted = muted,
            talking = talking,
            halfDuplex = speaking && !voiceBargeIn() && !options.textOnly,
            notice = notice,
        )
    }

    private fun uiTurns(): List<UiTurn> = records.filter { it.text.isNotBlank() }.map { record ->
        val text = record.text.toString().trim()
        val spoken = if (record.role == Role.ASSISTANT && record.key == currentAnswerKey && player != null && !record.interrupted) {
            val received = player.receivedMs(record.key)
            if (received <= 0) 0 else (text.length * (player.playedMs(record.key).toDouble() / received)).toInt().coerceIn(0, text.length)
        } else {
            text.length
        }
        UiTurn(record.key, record.role, text, if (speaking) spoken else text.length, record.interrupted)
    }

    private fun cost(): Double = CostMeter(model.pricing).apply {
        addInputAudio(inputAudioMs.get())
        addOutputAudio(outputAudioMs.get())
        if (tokens != TokenUsage()) addTokens(tokens)
    }.usd

    // ── End ─────────────────────────────────────────────────────────────────────────────────────

    private fun finish(error: SessionError?) {
        if (finished) return
        tick()
        finished = true
        finalError = error
        micOpen = false
        headphones.stop()
        val finalTurns = uiTurns()
        microphone.stop()
        player?.release()
        session.close()
        if (error != null) haptics.error() else haptics.ended()

        val usd = cost()
        val turns = records.filter { it.text.isNotBlank() }.map { Turn(it.role, it.text.toString().trim(), it.at, it.interrupted) }
        val worthKeeping = turns.any { it.role == Role.USER || it.role == Role.ASSISTANT }
        val conversation = if (options.saveHistory && worthKeeping) {
            Conversation(
                id = conversationId,
                startedAt = startedAtWall,
                endedAt = System.currentTimeMillis(),
                provider = providerKey,
                model = model.id,
                turns = turns,
                costUsd = usd,
            )
        } else {
            null
        }
        _state.value = _state.value.copy(
            phase = if (error != null) Phase.ERROR else Phase.ENDED,
            turns = finalTurns,
            micLevel = 0f,
            speechLevel = 0f,
            costUsd = usd,
            error = error,
            notice = null,
            saved = conversation != null,
        )
        scope.launch {
            onSave(conversation, usd)
            onFinished()
        }
    }

    /**
     * Debug builds log the conversation as it happens (adb logcat -s ParleyEvent), so emulator tests
     * can follow what was heard and answered. Release builds log only errors, without any content.
     */
    private fun debugLog(event: VoiceEvent) {
        when (event) {
            is VoiceEvent.Closed -> event.error?.let { Log.w(TAG, "closed: ${it.kind}") }
            else -> Unit
        }
        if (!BuildConfig.DEBUG) return
        val line = when (event) {
            is VoiceEvent.AssistantAudio -> return
            is VoiceEvent.UserTranscript -> "user[${event.turnKey}]${if (event.replace) "=" else "+"} ${event.text}"
            is VoiceEvent.AssistantTranscript -> "assistant[${event.turnKey}]+ ${event.delta}"
            is VoiceEvent.ToolCall -> "tool ${event.name} ${event.arguments}"
            is VoiceEvent.Closed -> "closed ${event.error ?: "by request"}"
            else -> event.toString()
        }
        Log.d(TAG, line)
    }

    private var micPeak = 0f
    private var micPeakSince = 0L

    /** Debug: once a second, how loud the microphone got — proves injected speech reaches the watch. */
    private fun logMicPeak(now: Long) {
        micPeak = maxOf(micPeak, if (micOpen) micLevel else 0f)
        if (now - micPeakSince < 1_000) return
        if (micPeak > 0.05f) Log.d(TAG, "mic peak %.2f".format(micPeak))
        micPeak = 0f
        micPeakSince = now
    }

    private companion object {
        const val TAG = "ParleyEvent"
        const val TICK_MS = 66L
        const val SPEECH_LEVEL = 0.45f
        const val SPEAKING_TAIL_MS = 400L
    }
}
