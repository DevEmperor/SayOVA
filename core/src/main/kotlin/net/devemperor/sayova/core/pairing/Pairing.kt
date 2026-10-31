package net.devemperor.sayova.core.pairing

import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.hybrid.HpkeParameters
import com.google.crypto.tink.hybrid.HpkePrivateKey
import com.google.crypto.tink.hybrid.HpkePublicKey
import com.google.crypto.tink.hybrid.internal.HpkeDecrypt
import com.google.crypto.tink.hybrid.internal.HpkeEncrypt
import com.google.crypto.tink.subtle.X25519
import com.google.crypto.tink.util.Bytes
import com.google.crypto.tink.util.SecretBytes
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Key handover from a browser to the watch (PLAN.md §5).
 *
 * The watch makes a one-time X25519 key pair and shows its public half in a QR code. The pairing page
 * encrypts the API key to it with HPKE (RFC 9180: DHKEM-X25519-HKDF-SHA256, HKDF-SHA256,
 * AES-128-GCM) and posts only the ciphertext to the relay. Without the private key, which never
 * leaves the watch's memory, nobody on the way can read it. The relay and the page live in pair/.
 *
 * Wire format of a sealed message: the 32-byte encapsulated key followed by the AEAD ciphertext, the
 * same layout the page produces. The HPKE info string binds it to one pairing id.
 */
object PairingCrypto {
    const val VERSION = 1

    private val parameters: HpkeParameters = HpkeParameters.builder()
        .setKemId(HpkeParameters.KemId.DHKEM_X25519_HKDF_SHA256)
        .setKdfId(HpkeParameters.KdfId.HKDF_SHA256)
        .setAeadId(HpkeParameters.AeadId.AES_128_GCM)
        .setVariant(HpkeParameters.Variant.NO_PREFIX)
        .build()

    fun info(pairingId: String): ByteArray = "sayova-pair-v$VERSION|$pairingId".toByteArray()

    class KeyPair internal constructor(private val privateKey: ByteArray, val publicKey: ByteArray) {
        fun open(sealed: ByteArray, pairingId: String): ByteArray {
            val key = HpkePrivateKey.create(publicKeyOf(publicKey), SecretBytes.copyFrom(privateKey, InsecureSecretKeyAccess.get()))
            return HpkeDecrypt.create(key).decrypt(sealed, info(pairingId))
        }

        /** Forget the private half once the key has arrived (or the user gave up). */
        fun wipe() = privateKey.fill(0)
    }

    fun generate(): KeyPair {
        val private = X25519.generatePrivateKey()
        return KeyPair(private, X25519.publicFromPrivate(private))
    }

    /** For tests: a key pair from fixed bytes. */
    fun fromPrivateKey(privateKey: ByteArray): KeyPair = KeyPair(privateKey.copyOf(), X25519.publicFromPrivate(privateKey))

    /** What the page does; used by the tests to prove both sides agree. */
    fun seal(publicKey: ByteArray, plaintext: ByteArray, pairingId: String): ByteArray =
        HpkeEncrypt.create(publicKeyOf(publicKey)).encrypt(plaintext, info(pairingId))

    private fun publicKeyOf(bytes: ByteArray) = HpkePublicKey.create(parameters, Bytes.copyFrom(bytes), null)

    /**
     * Six digits both screens show, so the user can see that the watch received exactly what this
     * browser sent: SHA-256 over the watch's public key and the encapsulated key, first four bytes as
     * an unsigned number, modulo one million.
     */
    fun verificationCode(publicKey: ByteArray, sealed: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").apply {
            update(publicKey)
            update(sealed, 0, ENC_LENGTH.coerceAtMost(sealed.size))
        }.digest()
        val number = ((digest[0].toLong() and 0xFF) shl 24) or ((digest[1].toLong() and 0xFF) shl 16) or
            ((digest[2].toLong() and 0xFF) shl 8) or (digest[3].toLong() and 0xFF)
        return "%06d".format(number % 1_000_000)
    }

    const val ENC_LENGTH = 32
}

/** What the page encrypts: the provider and its key. */
@Serializable
data class PairingPayload(
    val v: Int = PairingCrypto.VERSION,
    val provider: String,
    val apiKey: String,
) {
    fun encode(): ByteArray = json.encodeToString(serializer(), this).toByteArray()

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun decode(bytes: ByteArray): PairingPayload = json.decodeFromString(serializer(), bytes.decodeToString())
    }
}

object PairingIds {
    private val random = SecureRandom()

    /** 128 random bits, base64url without padding (22 characters). */
    fun newId(): String = Base64Url.encode(ByteArray(16).also(random::nextBytes))

    fun isValid(id: String) = id.length == 22 && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }
}

object Base64Url {
    fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    fun decode(text: String): ByteArray = Base64.getUrlDecoder().decode(text)
}

/**
 * The address in the QR code. Everything after # stays in the browser: no server ever sees it.
 * [provider] only preselects the choice on the page.
 */
object PairingLink {
    fun build(baseUrl: String, pairingId: String, publicKey: ByteArray, provider: String? = null): String =
        "${baseUrl.trimEnd('/')}/#v=${PairingCrypto.VERSION}&id=$pairingId&pk=${Base64Url.encode(publicKey)}" +
            (provider?.let { "&p=$it" } ?: "")
}
