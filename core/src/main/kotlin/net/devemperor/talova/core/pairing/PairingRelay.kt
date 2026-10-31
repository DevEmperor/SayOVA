package net.devemperor.talova.core.pairing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The watch's side of the relay (pair/src/worker.ts): announce a pairing slot, then wait for the
 * sealed key with long polls. Blocking calls; run them off the main thread.
 */
class PairingRelay(baseClient: OkHttpClient, baseUrl: String) {
    private val base = baseUrl.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }

    // Long polls hold the request open for up to WAIT_SECONDS, so the read timeout must be longer.
    private val client = baseClient.newBuilder().readTimeout(WAIT_SECONDS + 10L, TimeUnit.SECONDS).build()

    sealed interface Poll {
        data class Delivered(val sealed: ByteArray) : Poll
        data object Waiting : Poll
        /** The slot timed out (ten minutes) or was already used; start over with a new code. */
        data object Expired : Poll
    }

    /** Tells the relay this pairing id is in use, so the page can tell a fresh code from an old one. */
    fun open(pairingId: String) {
        val request = Request.Builder().url("$base/v1/pair/$pairingId/open").post(ByteArray(0).toRequestBody()).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Relay answered ${response.code}")
        }
    }

    fun poll(pairingId: String): Poll {
        val request = Request.Builder().url("$base/v1/pair/$pairingId?wait=$WAIT_SECONDS").get().build()
        client.newCall(request).execute().use { response ->
            return when (response.code) {
                200 -> {
                    val body = json.parseToJsonElement(response.body.string()).jsonObject
                    val sealed = body["sealed"]?.jsonPrimitive?.contentOrNull ?: throw IOException("Malformed relay answer")
                    Poll.Delivered(Base64Url.decode(sealed))
                }
                204 -> Poll.Waiting
                404, 410 -> Poll.Expired
                else -> throw IOException("Relay answered ${response.code}")
            }
        }
    }

    companion object {
        const val WAIT_SECONDS = 25
    }
}
