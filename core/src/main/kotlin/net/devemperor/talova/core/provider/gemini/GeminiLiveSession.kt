package net.devemperor.talova.core.provider.gemini

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.JsonObject
import net.devemperor.talova.core.audio.AudioGate
import net.devemperor.talova.core.audio.Pcm
import net.devemperor.talova.core.session.ErrorKind
import net.devemperor.talova.core.session.SessionConfig
import net.devemperor.talova.core.session.SessionError
import net.devemperor.talova.core.session.SessionErrors
import net.devemperor.talova.core.session.VoiceEvent
import net.devemperor.talova.core.session.VoiceSession
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A conversation with a Gemini Live model.
 *
 * A Live connection lasts about ten minutes; before it ends the server sends `goAway`. The session then
 * opens a new connection with the latest resumption handle, so a long conversation (or a dropped
 * network) continues where it was instead of ending.
 */
class GeminiLiveSession(
    private val config: SessionConfig,
    private val client: OkHttpClient,
) : VoiceSession {

    private val channel = Channel<VoiceEvent>(Channel.UNLIMITED)
    override val events: Flow<VoiceEvent> = channel.receiveAsFlow()
    override val inputSampleRate = GeminiProtocol.INPUT_RATE
    override val outputSampleRate = GeminiProtocol.OUTPUT_RATE

    private val translator = GeminiTranslator()
    // Speech while the session connects (the user may start talking at once) or reconnects survives
    // the handshake instead of being cut off.
    private val gate = AudioGate(maxBufferedBytes = Pcm.bytesFor(AudioGate.HOLD_MS, GeminiProtocol.INPUT_RATE))
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "gemini-session").apply { isDaemon = true } }

    @Volatile private var socket: WebSocket? = null
    @Volatile private var closed = false
    @Volatile private var everReady = false
    @Volatile private var reconnectAttempts = 0

    override fun connect() = open(resumptionHandle = null)

    private fun open(resumptionHandle: String?) {
        if (closed) return
        val request = Request.Builder().url(GeminiProtocol.url(config.apiKey)).build()
        socket = client.newWebSocket(request, Listener(resumptionHandle))
    }

    private inner class Listener(private val resumptionHandle: String?) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(GeminiProtocol.setup(config, resumptionHandle))
        }

        override fun onMessage(webSocket: WebSocket, text: String) = handle(webSocket, text)

        // Gemini sends its JSON as binary frames.
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(webSocket, bytes.utf8())

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            if (webSocket !== socket || closed) return
            if (code == 1000 && reason.isBlank()) {
                lostConnection(SessionError(ErrorKind.SERVER, "The session was ended by the server."))
            } else {
                val error = SessionErrors.fromText(reason) ?: SessionError(ErrorKind.SERVER, reason.ifBlank { "Closed ($code)" })
                // Errors in the request itself will not get better on a new connection.
                if (error.kind == ErrorKind.SERVER) lostConnection(error) else finish(error)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (webSocket !== socket || closed) return
            if (response != null && !everReady) {
                finish(SessionErrors.fromHttp(response.code, runCatching { response.body.string() }.getOrNull()))
            } else {
                lostConnection(SessionErrors.fromThrowable(t))
            }
        }
    }

    private fun handle(webSocket: WebSocket, text: String) {
        if (webSocket !== socket || closed) return
        val events = translator.translate(text) { control ->
            when (control) {
                GeminiTranslator.Control.SetupComplete -> {
                    everReady = true
                    reconnectAttempts = 0
                    gate.open { pcm -> socket?.send(GeminiProtocol.audio(pcm)) }
                    channel.trySend(VoiceEvent.Ready)
                }
                is GeminiTranslator.Control.GoAway -> reconnect()
            }
        }
        events.forEach { channel.trySend(it) }
    }

    /** Continues on a new connection when the server allows it, otherwise the session ends. */
    private fun lostConnection(error: SessionError) {
        val handle = translator.resumptionHandle
        if (handle == null || reconnectAttempts >= MAX_RECONNECTS) {
            finish(error)
            return
        }
        reconnectAttempts++
        val delayMs = BACKOFF_MS.getOrElse(reconnectAttempts - 1) { BACKOFF_MS.last() }
        gate.hold()
        channel.trySend(VoiceEvent.Reconnecting)
        scheduler.schedule({ open(handle) }, delayMs, TimeUnit.MILLISECONDS)
    }

    /** Planned switch to a new connection after `goAway`; no backoff, the old one still works for now. */
    private fun reconnect() {
        val handle = translator.resumptionHandle ?: return
        val old = socket
        gate.hold()
        channel.trySend(VoiceEvent.Reconnecting)
        socket = null
        old?.close(1000, null)
        scheduler.execute { open(handle) }
    }

    override fun sendAudio(pcm: ByteArray) {
        if (!closed) gate.send(pcm)
    }

    override fun endOfUserAudio() {
        socket?.send(GeminiProtocol.audioStreamEnd())
    }

    override fun sendText(text: String) {
        socket?.send(GeminiProtocol.text(text))
    }

    override fun sendToolResult(callId: String, name: String, result: JsonObject) {
        socket?.send(GeminiProtocol.toolResponse(callId, name, result))
    }

    /**
     * Gemini has no message to cancel an answer while it detects speech itself, so the rest of the
     * current answer is simply not passed on. The next turn starts clean.
     */
    override fun interrupt(turnKey: String?, playedMs: Long) {
        translator.dropCurrentModelTurn()
    }

    override fun close() {
        finish(null)
    }

    private fun finish(error: SessionError?) {
        if (closed) return
        closed = true
        gate.hold()
        socket?.close(1000, null)
        socket = null
        channel.trySend(VoiceEvent.Closed(error))
        channel.close()
        scheduler.shutdown()
    }

    private companion object {
        const val MAX_RECONNECTS = 3
        val BACKOFF_MS = listOf(300L, 1_500L, 4_000L)
    }
}
