package net.devemperor.parley.ui.setup

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.devemperor.parley.BuildConfig
import net.devemperor.parley.Diagnostics
import net.devemperor.parley.core.pairing.PairingCrypto
import net.devemperor.parley.core.pairing.PairingIds
import net.devemperor.parley.core.pairing.PairingLink
import net.devemperor.parley.core.pairing.PairingPayload
import net.devemperor.parley.core.pairing.PairingRelay
import net.devemperor.parley.core.provider.KeyCheck
import net.devemperor.parley.core.provider.ProviderCatalog
import net.devemperor.parley.core.provider.ProviderId
import net.devemperor.parley.parley
import java.io.IOException

sealed interface PairingState {
    data object Preparing : PairingState

    /** The QR code is up and the watch waits for the phone. [offline] after repeated failures. */
    data class Waiting(val link: String, val provider: ProviderId, val offline: Boolean = false) : PairingState

    /** A key arrived; the user compares [code] with the phone before it is used. */
    data class Received(val code: String, val provider: ProviderId) : PairingState

    data class Checking(val provider: ProviderId) : PairingState

    data class Connected(val provider: ProviderId, val verified: Boolean) : PairingState

    data class Failed(val reason: Reason, val detail: String? = null) : PairingState

    enum class Reason { EXPIRED, REJECTED_KEY, NO_CONNECTION, BROKEN }
}

/**
 * The watch side of the QR handover (PLAN.md §5): make a one-time key pair, announce the code to the
 * relay, wait for the sealed key, open it, let the user compare the code, check the key with the
 * provider, store it. The private key only ever exists in this object's memory.
 */
class PairingViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application.parley
    private val relay = PairingRelay(app.http, BuildConfig.PAIR_BASE_URL)

    private val _state = MutableStateFlow<PairingState>(PairingState.Preparing)
    val state: StateFlow<PairingState> = _state.asStateFlow()

    private var job: Job? = null
    private var keyPair: PairingCrypto.KeyPair? = null
    private var pending: PairingPayload? = null

    fun start(provider: ProviderId) {
        job?.cancel()
        forget()
        _state.value = PairingState.Preparing
        job = viewModelScope.launch {
            val pair = PairingCrypto.generate()
            keyPair = pair
            val id = PairingIds.newId()
            val link = PairingLink.build(BuildConfig.PAIR_BASE_URL, id, pair.publicKey, provider.key)
            // Lets an emulator be paired without scanning its screen. The link holds only public data.
            if (Diagnostics.enabled) android.util.Log.d("ParleyPair", link)
            var failures = 0
            // Announce the code; keep trying while offline, the QR code is shown either way.
            _state.value = PairingState.Waiting(link, provider)
            while (true) {
                try {
                    withContext(Dispatchers.IO) { relay.open(id) }
                    break
                } catch (_: IOException) {
                    failures++
                    _state.value = PairingState.Waiting(link, provider, offline = failures >= 2)
                    delay(3_000)
                }
            }
            failures = 0
            val deadline = System.currentTimeMillis() + 10 * 60_000
            while (System.currentTimeMillis() < deadline) {
                val poll = try {
                    withContext(Dispatchers.IO) { relay.poll(id) }
                } catch (_: IOException) {
                    failures++
                    _state.value = PairingState.Waiting(link, provider, offline = failures >= 2)
                    delay(3_000)
                    continue
                }
                failures = 0
                when (poll) {
                    PairingRelay.Poll.Waiting -> _state.value = PairingState.Waiting(link, provider)
                    PairingRelay.Poll.Expired -> break
                    is PairingRelay.Poll.Delivered -> {
                        receive(pair, poll.sealed, id)
                        return@launch
                    }
                }
            }
            forget()
            _state.value = PairingState.Failed(PairingState.Reason.EXPIRED)
        }
    }

    private fun receive(pair: PairingCrypto.KeyPair, sealed: ByteArray, id: String) {
        val payload = runCatching { PairingPayload.decode(pair.open(sealed, id)) }.getOrNull()
        val provider = payload?.let { ProviderId.fromKey(it.provider) }
        if (payload == null || provider == null || payload.apiKey.isBlank()) {
            forget()
            _state.value = PairingState.Failed(PairingState.Reason.BROKEN)
            return
        }
        pending = payload
        _state.value = PairingState.Received(
            code = PairingCrypto.verificationCode(pair.publicKey, sealed),
            provider = provider,
        )
        forget()
    }

    /** The codes match: check the key with the provider and keep it. */
    fun confirm() {
        val payload = pending ?: return
        val provider = ProviderId.fromKey(payload.provider) ?: return
        save(provider, payload.apiKey.trim())
    }

    private fun save(provider: ProviderId, apiKey: String) {
        pending = null
        _state.value = PairingState.Checking(provider)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { KeyCheck(app.http).check(provider, apiKey) }
            when (result) {
                is KeyCheck.Result.Invalid -> _state.value = PairingState.Failed(PairingState.Reason.REJECTED_KEY, result.reason)
                else -> {
                    app.keys.put(provider, apiKey)
                    app.settings.update { it.copy(provider = provider) }
                    _state.value = PairingState.Connected(provider, verified = result == KeyCheck.Result.Valid)
                }
            }
        }
    }

    fun reject() {
        pending = null
        _state.value = PairingState.Preparing
    }

    private fun forget() {
        keyPair?.wipe()
        keyPair = null
    }

    override fun onCleared() {
        forget()
        pending = null
    }

    companion object {
        fun providerName(id: ProviderId) = ProviderCatalog.get(id).displayName
    }
}
