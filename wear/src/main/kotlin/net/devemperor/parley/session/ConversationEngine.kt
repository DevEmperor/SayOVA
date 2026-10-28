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
import net.devemperor.parley.Diagnostics
import net.devemperor.parley.R
import net.devemperor.parley.audio.Haptics
import net.devemperor.parley.audio.Headphones
import net.devemperor.parley.audio.Microphone
import net.devemperor.parley.audio.SpeechPlayer
import net.devemperor.parley.audio.SpeechVolume
import net.devemperor.parley.core.audio.BargeInDetector
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
import net.devemperor.parley.core.tools.WatchFeature
import net.devemperor.parley.data.BargeIn
import net.devemperor.parley.features.WatchFeatures
import net.devemperor.parley.net.Transport
import net.devemperor.parley.ui.labelRes
import net.devemperor.parley.ui.workingRes
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
    /** The conversation to keep (null when not saved), its cost and its talk time. */
    private val onSave: suspend (Conversation?, Double, Long) -> Unit,
    /** Parley's own volume at the end, to start the next conversation with. */
    private val onVolume: suspend (Int) -> Unit,
    private val onFinished: () -> Unit,
) {
    data class Options(
        val textOnly: Boolean,
        val pushToTalk: Boolean,
        val bargeIn: BargeIn,
        val autoEndSeconds: Int,
        val showCost: Boolean,
        val saveHistory: Boolean,
        /** Parley's own volume from the last conversation; -1 before the first. */
        val speechVolume: Int,
        /** Start with Parley's voice switched off (see [setSoundOff]). */
        val soundOff: Boolean,
        /** A conversation from the history this one carries on: shown first, saved into the same entry. */
        val continues: Conversation? = null,
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

    private val records = options.continues?.turns.orEmpty().mapIndexedTo(mutableListOf()) { index, turn ->
        Record("earlier-$index", turn.role, turn.at).apply {
            text.append(turn.text)
            interrupted = turn.interrupted
        }
    }
    private val conversationId = options.continues?.id ?: UUID.randomUUID().toString()
    private val startedAtWall = options.continues?.startedAt ?: System.currentTimeMillis()
    private var readyAt = 0L
    private var ready = false
    private var reconnecting = false
    private var finished = false
    private var finalError: SessionError? = null

    /** Read once: whether this conversation is logged (see Diagnostics). */
    private val diagnostics = Diagnostics.enabled
    private val player: SpeechPlayer? = if (options.textOnly) null else SpeechPlayer(session.outputSampleRate)
    private val volume: SpeechVolume? = if (options.textOnly) null else SpeechVolume(context)
    private val microphone = Microphone(session.inputSampleRate, ::onMicAudio, ::onMicLevel)

    // Touched by the microphone thread.
    @Volatile private var micOpen = false
    @Volatile private var micLevel = 0f
    private val inputAudioMs = AtomicLong()
    private val outputAudioMs = AtomicLong()
    private var tokens = TokenUsage()

    private val headphones = Headphones(context)

    // Talking over an answer on the watch speaker (see BargeInDetector). Touched by the mic thread.
    private val bargeIn = BargeInDetector()
    @Volatile private var listeningForBargeIn = false
    @Volatile private var bargeInHeard = false
    /** The last moments of microphone audio held back during an answer, so the user's first words survive. */
    private val preRoll = ArrayDeque<ByteArray>()
    /** Set when the user talked over an answer: the held-back audio goes out once the microphone opens. */
    @Volatile private var keepPreRoll = false

    private var muted = false
    private var soundOff = options.soundOff && !options.textOnly
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
    @Volatile private var activeFeature: WatchFeature? = null
    @Volatile private var usedFeatures: List<WatchFeature> = emptyList()

    fun start() {
        volume?.begin(options.speechVolume)
        player?.setMuted(soundOff)
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
            finish(SessionError(ErrorKind.REJECTED, context.getString(R.string.error_microphone_unavailable)))
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

    /**
     * Switches Parley's voice off or on. Answers keep playing silently, so the transcript still lights
     * up at the pace of the voice and talking over an answer still interrupts it. The echo comes and
     * goes with the sound, so the barge-in detector learns it anew.
     */
    fun setSoundOff(off: Boolean) {
        if (player == null || soundOff == off) return
        soundOff = off
        player.setMuted(off)
        bargeIn.startAnswer(SystemClock.elapsedRealtime())
        // One short line under the orb: the speaker icon already tells that the sound is back.
        showNotice(context.getString(if (off) R.string.notice_sound_off else R.string.notice_bezel_volume), 2_500)
        tick()
    }

    /**
     * One volume step from the screen (+1 louder, -1 quieter); returns the volume now, null in silent
     * mode. The answer's echo changes with it, so the barge-in detector learns it anew.
     */
    fun stepVolume(direction: Int): Int? {
        val level = volume?.step(direction) ?: return null
        bargeIn.startAnswer(SystemClock.elapsedRealtime())
        return level
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
        if (!micOpen) {
            if (listeningForBargeIn) synchronized(preRoll) {
                preRoll.addLast(pcm)
                while (preRoll.size > PRE_ROLL_CHUNKS) preRoll.removeFirst()
            }
            return
        }
        synchronized(preRoll) {
            while (preRoll.isNotEmpty()) session.sendAudio(preRoll.removeFirst())
            keepPreRoll = false
        }
        session.sendAudio(pcm)
        inputAudioMs.addAndGet(Pcm.durationMs(pcm.size, session.inputSampleRate))
    }

    private fun onMicLevel(level: Float) {
        micLevel = if (micOpen) level else 0f
        if (diagnostics && listeningForBargeIn) echoPeak = maxOf(echoPeak, level)
        if (listeningForBargeIn && !bargeInHeard) {
            val played = player?.recentLevel(ECHO_WINDOW_MS) ?: 0f
            if (bargeIn.onFrame(level, played, SystemClock.elapsedRealtime())) {
                bargeInHeard = true
                if (diagnostics) Log.d(TAG, "barge-in: mic %.2f, played %.2f, coupling %.2f".format(level, played, bargeIn.learnedCoupling))
            }
        }
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
     * Whether the microphone may stream to the provider while Parley speaks. Over the watch speaker
     * it hears every answer, which the provider takes for the user talking over it: answers break
     * off. So in the automatic mode the audio stays on the watch during an answer and BargeInDetector
     * listens for the user instead; through headphones there is no echo and the microphone streams.
     */
    private fun streamWhileSpeaking(): Boolean = when (options.bargeIn) {
        BargeIn.ON -> true
        BargeIn.OFF -> false
        BargeIn.AUTO -> headphones.connected
    }

    private fun runTool(call: VoiceEvent.ToolCall) {
        pendingTools++
        touch()
        if (call.name == net.devemperor.parley.core.tools.ParleyTools.SEARCH_WEB) showNotice(context.getString(R.string.notice_searching), 8_000)
        // A watch feature shows itself while in use, like Android's own indicators: its icon and
        // what it does, or that it waits for the user's permission.
        val feature = WatchFeature.forTool(call.name)
        if (feature != null) {
            activeFeature = feature
            if (feature !in usedFeatures) usedFeatures = usedFeatures + feature
            val waiting = !WatchFeatures.granted(context, feature)
            showNotice(
                if (waiting) context.getString(R.string.notice_allow, context.getString(feature.labelRes())) else context.getString(feature.workingRes()),
                FEATURE_NOTICE_MS,
            )
        }
        scope.launch {
            val outcome = toolRunner.run(call) {
                // Allowed just now: from asking for permission on to what the feature does.
                if (feature != null) showNotice(context.getString(feature.workingRes()), FEATURE_NOTICE_MS)
            }
            session.sendToolResult(call.callId, call.name, outcome.result)
            pendingTools--
            if (feature != null) {
                activeFeature = null
                if (outcome.note == null) noticeUntil = 0
            }
            outcome.savedNote?.let { record("note-${call.callId}", Role.NOTE).text.append(it) }
            outcome.note?.let { note ->
                if (outcome.savedNote == null) record("tool-${call.callId}", Role.TOOL).text.append(note)
                haptics.action()
                showNotice(outcome.notice ?: note, 2_500)
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

        // The answer's echo still reaches the microphone for a moment after the last of it was played.
        var echoTail = !options.textOnly && lastAudibleAt > 0 && now - lastAudibleAt < ECHO_WINDOW_MS
        if (bargeInHeard) {
            bargeInHeard = false
            if (speaking) interruptAnswer()
            speaking = false
            echoTail = false
            lastAudibleAt = 0
            keepPreRoll = true
        }
        val listening = ready && !muted && (!_state.value.pushToTalk || talking)
        if (!listening) keepPreRoll = false
        val gated = (speaking || echoTail) && !streamWhileSpeaking()
        listeningForBargeIn = listening && gated && options.bargeIn == BargeIn.AUTO && !options.textOnly
        // Held-back audio is the user's first words when they talked over the answer, and otherwise
        // only the answer's echo, which must not reach the provider when the microphone opens.
        if (!listeningForBargeIn && !keepPreRoll) synchronized(preRoll) { preRoll.clear() }
        micOpen = listening && !gated
        if (micOpen && micLevel > SPEECH_LEVEL) touch()
        if (diagnostics) logMicPeak(now)
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
            bargeIn.startAnswer(now)
            haptics.answering()
        }
        if (phase != lastPhase && diagnostics) Log.d(TAG, "phase ${lastPhase.name} -> ${phase.name} (mic ${if (micOpen) "open" else "closed"})")
        lastPhase = phase

        if (endAfterSpeech && !speaking && pendingTools == 0) {
            end()
        } else if (options.autoEndSeconds > 0 && phase == Phase.LISTENING && now - lastActivityAt > options.autoEndSeconds * 1000L) {
            end()
        }
        // The session reports its close at once, so end() may have finished the conversation right
        // here; this tick's state would then overwrite the final one and leave the screen on "Listening".
        if (finished) return

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
            soundOff = soundOff,
            talking = talking,
            halfDuplex = speaking && options.bargeIn == BargeIn.OFF && !options.textOnly,
            notice = notice,
            activeFeature = activeFeature,
            usedFeatures = usedFeatures,
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
        val ownVolume = volume?.end()
        session.close()
        if (error != null) haptics.error() else haptics.ended()

        val usd = cost()
        val talkMs = if (readyAt > 0) SystemClock.elapsedRealtime() - readyAt else 0L
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
                costUsd = usd + (options.continues?.costUsd ?: 0.0),
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
            ownVolume?.let { onVolume(it) }
            onSave(conversation, usd, talkMs)
            onFinished()
        }
    }

    /**
     * With diagnostics on (see Diagnostics) the conversation is logged as it happens (adb logcat -s
     * ParleyEvent), so tests can follow what was heard and answered. Otherwise only errors are logged,
     * without any content.
     */
    private fun debugLog(event: VoiceEvent) {
        when (event) {
            is VoiceEvent.Closed -> event.error?.let { Log.w(TAG, "closed: ${it.kind}") }
            else -> Unit
        }
        if (!diagnostics) return
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
    @Volatile private var echoPeak = 0f

    /**
     * Debug: once a second, how loud the microphone got — proves injected speech reaches the watch —
     * and, during an answer, how loud Parley's own voice comes back (what the barge-in detector sees).
     */
    private fun logMicPeak(now: Long) {
        micPeak = maxOf(micPeak, if (micOpen) micLevel else 0f)
        if (now - micPeakSince < 1_000) return
        if (micPeak > 0.05f) Log.d(TAG, "mic peak %.2f".format(micPeak))
        if (echoPeak > 0.05f) Log.d(TAG, "echo peak %.2f, coupling %.2f".format(echoPeak, bargeIn.learnedCoupling))
        micPeak = 0f
        echoPeak = 0f
        micPeakSince = now
    }

    private companion object {
        const val TAG = "ParleyEvent"
        const val TICK_MS = 66L
        const val SPEECH_LEVEL = 0.45f
        const val SPEAKING_TAIL_MS = 400L
        /**
         * How far back the played answer counts as possible echo: output and input buffers plus the
         * room. The emulator's loop measured about 300 ms; watches are faster, but not all of them.
         */
        const val ECHO_WINDOW_MS = 600L
        /** Long enough for a heart rate measurement or the permission dialog; the outcome replaces it. */
        const val FEATURE_NOTICE_MS = 60_000L
        /**
         * 10 × 100 ms of microphone audio kept during an answer for when the user talks over it: when
         * the echo is loud the user is often recognised only in the answer's next pause.
         */
        const val PRE_ROLL_CHUNKS = 10
    }
}
