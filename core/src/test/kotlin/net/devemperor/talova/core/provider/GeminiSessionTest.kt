package net.devemperor.talova.core.provider

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.devemperor.talova.core.provider.gemini.GeminiLiveSession
import net.devemperor.talova.core.session.SessionConfig
import net.devemperor.talova.core.session.VoiceEvent
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Drives [GeminiLiveSession] against a fake Live server: the setup goes first, audio sent before
 * `setupComplete` is replayed after it, and a `goAway` moves the conversation to a new connection with
 * the resumption handle.
 */
class GeminiSessionTest {

    private class FakeLiveServer : WebSocketListener() {
        val received = LinkedBlockingQueue<String>()
        val sockets = LinkedBlockingQueue<WebSocket>()
        val all = java.util.concurrent.CopyOnWriteArrayList<WebSocket>()
        override fun onOpen(webSocket: WebSocket, response: Response) {
            sockets.add(webSocket)
            all.add(webSocket)
        }
        override fun onMessage(webSocket: WebSocket, text: String) {
            received.add(text)
        }
    }

    @Test
    fun `setup first, held audio replayed, goAway resumes`() = runBlocking {
        val fake = FakeLiveServer()
        val server = MockWebServer()
        repeat(2) { server.enqueue(MockResponse().withWebSocketUpgrade(fake)) }
        server.start()
        val client = OkHttpClient()
        val session = TestableGemini(server.url("/").toString().replace("http", "ws"), client)
        try {
            session.connect()
            session.sendAudio(byteArrayOf(1, 2))
            val socket = fake.sockets.poll(5, TimeUnit.SECONDS)!!
            val setup = fake.received.poll(5, TimeUnit.SECONDS)!!
            assertTrue(setup.startsWith("""{"setup":"""))
            assertTrue(fake.received.poll(300, TimeUnit.MILLISECONDS) == null) // audio held back

            socket.send("""{"setupComplete":{}}""")
            withTimeout(5_000) { assertEquals(VoiceEvent.Ready, session.events.first()) }
            assertTrue(fake.received.poll(5, TimeUnit.SECONDS)!!.contains("realtimeInput"))

            socket.send("""{"sessionResumptionUpdate":{"newHandle":"H","resumable":true}}""")
            socket.send("""{"goAway":{"timeLeft":"5s"}}""")
            withTimeout(5_000) { assertEquals(VoiceEvent.Reconnecting, session.events.first()) }
            fake.sockets.poll(5, TimeUnit.SECONDS)!!
            val resumed = fake.received.poll(5, TimeUnit.SECONDS)!!
            assertTrue(resumed, resumed.contains(""""handle":"H""""))
        } finally {
            session.close()
            fake.all.forEach { it.close(1000, null) }
            server.shutdown()
        }
    }

    /** The real session with its URL pointed at the fake server. */
    private class TestableGemini(url: String, client: OkHttpClient) : net.devemperor.talova.core.session.VoiceSession by GeminiLiveSession(
        SessionConfig("k", "gemini-3.8-live", "Kore", "x", emptyList()),
        client.newBuilder().addInterceptor { chain ->
            val original = chain.request()
            chain.proceed(original.newBuilder().url(url.replace("ws", "http")).build())
        }.build(),
    )
}
