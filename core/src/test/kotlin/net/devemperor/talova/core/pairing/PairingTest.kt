package net.devemperor.talova.core.pairing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.GeneralSecurityException

class PairingTest {

    @Test
    fun `sealed payload opens on the watch`() {
        val pair = PairingCrypto.generate()
        val id = PairingIds.newId()
        val payload = PairingPayload(provider = "gemini", apiKey = "AIza-secret")
        val sealed = PairingCrypto.seal(pair.publicKey, payload.encode(), id)
        assertEquals(payload, PairingPayload.decode(pair.open(sealed, id)))
    }

    @Test
    fun `ciphertext is bound to its pairing id`() {
        val pair = PairingCrypto.generate()
        val sealed = PairingCrypto.seal(pair.publicKey, "x".toByteArray(), PairingIds.newId())
        try {
            pair.open(sealed, PairingIds.newId())
            fail("opened with the wrong pairing id")
        } catch (_: GeneralSecurityException) {
        }
    }

    @Test
    fun `verification code is six digits and depends on the message`() {
        val pair = PairingCrypto.generate()
        val id = PairingIds.newId()
        val a = PairingCrypto.seal(pair.publicKey, "a".toByteArray(), id)
        val b = PairingCrypto.seal(pair.publicKey, "a".toByteArray(), id)
        val codeA = PairingCrypto.verificationCode(pair.publicKey, a)
        assertTrue(Regex("\\d{6}").matches(codeA))
        assertEquals(codeA, PairingCrypto.verificationCode(pair.publicKey, a))
        // Every seal uses a fresh ephemeral key, so two messages never share a code by construction.
        assertNotEquals(codeA, PairingCrypto.verificationCode(pair.publicKey, b))
    }

    @Test
    fun `ids and links`() {
        val id = PairingIds.newId()
        assertTrue(PairingIds.isValid(id))
        val link = PairingLink.build("https://pair.example/", id, ByteArray(32) { 1 })
        assertEquals("https://pair.example/#v=1&id=$id&pk=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE", link)
    }

    /**
     * The same message sealed by the pairing page (pair/scripts/make-test-vector.mjs, using the page's
     * own HPKE library) must open here, and both sides must show the same code.
     */
    @Test
    fun `page and watch agree (test vector from pair)`() {
        val text = javaClass.getResource("/pairing-vector.json")!!.readText()
        val vector = Json.parseToJsonElement(text).jsonObject
        fun field(name: String) = vector[name]!!.jsonPrimitive.content
        val pair = PairingCrypto.fromPrivateKey(Base64Url.decode(field("privateKey")))
        assertArrayEquals(Base64Url.decode(field("publicKey")), pair.publicKey)
        val sealed = Base64Url.decode(field("sealed"))
        val payload = PairingPayload.decode(pair.open(sealed, field("pairingId")))
        assertEquals(field("provider"), payload.provider)
        assertEquals(field("apiKey"), payload.apiKey)
        assertEquals(field("code"), PairingCrypto.verificationCode(pair.publicKey, sealed))
    }

    @Test
    fun `relay client opens and polls`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setBody("""{"sealed":"AQID"}"""))
        server.enqueue(MockResponse().setResponseCode(410))
        server.start()
        try {
            val relay = PairingRelay(OkHttpClient(), server.url("/").toString())
            val id = PairingIds.newId()
            relay.open(id)
            assertEquals("/v1/pair/$id/open", server.takeRequest().path)
            assertEquals(PairingRelay.Poll.Waiting, relay.poll(id))
            assertEquals("/v1/pair/$id?wait=${PairingRelay.WAIT_SECONDS}", server.takeRequest().path)
            val delivered = relay.poll(id) as PairingRelay.Poll.Delivered
            assertArrayEquals(byteArrayOf(1, 2, 3), delivered.sealed)
            assertEquals(PairingRelay.Poll.Expired, relay.poll(id))
        } finally {
            server.shutdown()
        }
    }
}
