package net.devemperor.sayova.core.provider.openai

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.JsonObject
import net.devemperor.sayova.core.audio.AudioGate
import net.devemperor.sayova.core.audio.Pcm
import net.devemperor.sayova.core.session.ErrorKind
import net.devemperor.sayova.core.session.SessionConfig
import net.devemperor.sayova.core.session.SessionError
import net.devemperor.sayova.core.session.SessionErrors
import net.devemperor.sayova.core.session.VoiceEvent
import net.devemperor.sayova.core.session.VoiceSession
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * A conversation with an OpenAI Realtime model. Unlike Gemini, a Realtime session cannot be resumed
 * on a new connection, so a dropped network ends it (the history on the watch keeps what was said).
 */
class OpenAiRealtimeSession(
    private val config: SessionConfig,
    private val client: OkHttpClient,
) : VoiceSession {

    private val channel = Channel<VoiceEvent>(Channel.UNLIMITED)
    override val events: Flow<VoiceEvent> = channel.receiveAsFlow()
    override val inputSampleRate = OpenAiProtocol.RATE
    override val outputSampleRate = OpenAiProtocol.RATE

    private val translator = OpenAiTranslator()
    // The user may start talking while the session connects; that much of it waits for the handshake.
    private val gate = AudioGate(maxBufferedBytes = Pcm.bytesFor(AudioGate.HOLD_MS, OpenAiProtocol.RATE))

    @Volatile private var socket: WebSocket? = null
    @Volatile private var closed = false
    @Volatile private var ready = false

    override fun connect() {
        val request = Request.Builder()
            .url(OpenAiProtocol.url(config.model))
            .header("Authorization", "Bearer ${config.apiKey}")
            .build()
        socket = client.newWebSocket(request, listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(OpenAiProtocol.sessionUpdate(config))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (closed) return
            val events = synchronized(translator) {
                translator.translate(text) { control ->
                    when (control) {
                        OpenAiTranslator.Control.SessionReady -> if (!ready) {
                            ready = true
                            gate.open { pcm -> webSocket.send(OpenAiProtocol.appendAudio(pcm)) }
                            channel.trySend(VoiceEvent.Ready)
                        }
                        is OpenAiTranslator.Control.Failure -> {
                            if (!ready || OpenAiTranslator.isFatal(control.code, control.message)) {
                                finish(SessionErrors.fromText(control.message) ?: SessionError(ErrorKind.REJECTED, control.message))
                            } else {
                                channel.trySend(VoiceEvent.Warning(control.message))
                            }
                        }
                    }
                }
            }
            events.forEach { channel.trySend(it) }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            if (!closed) finish(SessionErrors.fromText(reason) ?: SessionError(ErrorKind.SERVER, reason.ifBlank { "The session was ended by the server." }))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (closed) return
            finish(
                if (response != null) SessionErrors.fromHttp(response.code, runCatching { response.body.string() }.getOrNull())
                else SessionErrors.fromThrowable(t),
            )
        }
    }

    override fun sendAudio(pcm: ByteArray) {
        if (!closed) gate.send(pcm)
    }

    /** With server-side turn detection the turn ends on silence, so push-to-talk hands it some. */
    override fun endOfUserAudio() {
        sendAudio(Pcm.silence(1_000, OpenAiProtocol.RATE))
    }

    override fun sendText(text: String) {
        socket?.send(OpenAiProtocol.userText(text))
        socket?.send(OpenAiProtocol.responseCreate())
    }

    /** Results go back one by one; the answer continues once every call of the response has one. */
    override fun sendToolResult(callId: String, name: String, result: JsonObject) {
        val socket = socket ?: return
        socket.send(OpenAiProtocol.functionOutput(callId, result))
        val allAnswered = synchronized(translator) {
            translator.pendingCalls.remove(callId)
            translator.pendingCalls.isEmpty()
        }
        if (allAnswered) socket.send(OpenAiProtocol.responseCreate())
    }

    /**
     * Stops the answer and tells the server how much of it was heard, so it does not believe it said
     * things the user never got to hear.
     */
    override fun interrupt(turnKey: String?, playedMs: Long) {
        val socket = socket ?: return
        val active = synchronized(translator) {
            translator.drop(turnKey)
            translator.responseActive
        }
        if (active) socket.send(OpenAiProtocol.responseCancel())
        if (turnKey != null) socket.send(OpenAiProtocol.truncate(turnKey, playedMs.coerceAtLeast(0)))
    }

    override fun close() = finish(null)

    private fun finish(error: SessionError?) {
        if (closed) return
        closed = true
        gate.hold()
        socket?.close(1000, null)
        socket = null
        channel.trySend(VoiceEvent.Closed(error))
        channel.close()
    }
}
