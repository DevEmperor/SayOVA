package net.devemperor.parley.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.devemperor.parley.core.provider.ProviderId
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The users' API keys, encrypted with an AES key that lives in the Android Keystore and cannot be
 * exported. Backups are off for the whole app, so the keys never leave the watch.
 */
class KeyVault(context: Context) {
    private val prefs = context.getSharedPreferences("keys", Context.MODE_PRIVATE)
    private val _connected = MutableStateFlow(ProviderId.entries.filter { prefs.contains(it.key) }.toSet())

    /** Providers that have a key stored. */
    val connected: StateFlow<Set<ProviderId>> = _connected.asStateFlow()

    fun get(provider: ProviderId): String? {
        val stored = prefs.getString(provider.key, null) ?: return null
        return runCatching {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes, 0, IV_LENGTH))
            String(cipher.doFinal(bytes, IV_LENGTH, bytes.size - IV_LENGTH))
        }.getOrNull()
    }

    fun put(provider: ProviderId, apiKey: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val sealed = cipher.iv + cipher.doFinal(apiKey.toByteArray())
        prefs.edit { putString(provider.key, Base64.encodeToString(sealed, Base64.NO_WRAP)) }
        _connected.value = _connected.value + provider
    }

    fun remove(provider: ProviderId) {
        prefs.edit { remove(provider.key) }
        _connected.value = _connected.value - provider
    }

    /** "…a1B2", enough to recognise a key without showing it. */
    fun hint(provider: ProviderId): String? = get(provider)?.let(::hintOf)

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "parley_api_keys"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LENGTH = 12

        fun hintOf(apiKey: String): String = "…" + apiKey.takeLast(4)
    }
}
