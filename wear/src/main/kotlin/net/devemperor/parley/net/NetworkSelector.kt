package net.devemperor.parley.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory

/** How the watch is online; named on screen by ui/Labels.kt. */
enum class Transport { WIFI, CELLULAR, BLUETOOTH, OTHER, NONE }

/**
 * Streaming audio needs more bandwidth than the Bluetooth link to the phone reliably gives, and Wear
 * OS prefers that link to save battery. For the length of a conversation Parley therefore asks for
 * Wi-Fi or LTE and routes its sockets through it, falling back to whatever is there if neither comes
 * up in time. The request is dropped again when the conversation ends, so the radio can sleep.
 *
 * Waking the Wi-Fi takes seconds, which made the start of a conversation slow; so the home screen
 * asks for it already while it shows ([prewarm]), and the network is usually up by the time the user
 * taps. Unused, that request lapses after [WARM_MS].
 *
 * Main thread only.
 */
class NetworkSelector(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    private val _fastNetwork = MutableStateFlow<Network?>(null)
    private val _transport = MutableStateFlow(Transport.NONE)
    val transport: StateFlow<Transport> = _transport.asStateFlow()

    private var callback: ConnectivityManager.NetworkCallback? = null
    /** A conversation holds the network; nothing lets it lapse meanwhile. */
    private var held = false
    private val handler = Handler(Looper.getMainLooper())
    private val lapse = Runnable { if (!held) drop() }

    /** Asks for the fast network without waiting for it; it lapses after [WARM_MS] unless a conversation takes it over. */
    fun prewarm() {
        request()
        handler.removeCallbacks(lapse)
        if (!held) handler.postDelayed(lapse, WARM_MS)
    }

    /** Holds a fast network for a conversation and waits up to [timeoutMs] for it. */
    suspend fun acquire(timeoutMs: Long = 4_000) {
        held = true
        handler.removeCallbacks(lapse)
        request()
        withTimeoutOrNull(timeoutMs) { _fastNetwork.first { it != null } }
        updateTransport()
    }

    /** The conversation is over. A home screen showing again keeps the network warm for the next one. */
    fun release() {
        held = false
        handler.removeCallbacks(lapse)
        handler.postDelayed(lapse, LINGER_MS)
    }

    private fun request() {
        if (callback == null) {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    _fastNetwork.value = network
                    updateTransport()
                }

                override fun onLost(network: Network) {
                    if (_fastNetwork.value == network) _fastNetwork.value = null
                    updateTransport()
                }
            }
            callback = cb
            runCatching { connectivity.requestNetwork(request, cb) }
        }
    }

    private fun drop() {
        callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        callback = null
        _fastNetwork.value = null
    }

    /**
     * A client whose sockets follow the fast network while it is there and the default network
     * otherwise. Decided per connection, so a resumed Gemini session picks up whatever is current.
     */
    fun client(base: OkHttpClient): OkHttpClient = base.newBuilder()
        .socketFactory(object : SocketFactory() {
            private fun factory(): SocketFactory = _fastNetwork.value?.socketFactory ?: getDefault()
            override fun createSocket(): Socket = factory().createSocket()
            override fun createSocket(host: String?, port: Int): Socket = factory().createSocket(host, port)
            override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
                factory().createSocket(host, port, localHost, localPort)
            override fun createSocket(host: InetAddress?, port: Int): Socket = factory().createSocket(host, port)
            override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
                factory().createSocket(address, port, localAddress, localPort)
        })
        .dns { host -> _fastNetwork.value?.getAllByName(host)?.toList() ?: Dns.SYSTEM.lookup(host) }
        .build()

    private fun updateTransport() {
        val network = _fastNetwork.value ?: connectivity.activeNetwork
        val caps = network?.let { connectivity.getNetworkCapabilities(it) }
        _transport.value = when {
            caps == null -> Transport.NONE
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Transport.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Transport.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> Transport.BLUETOOTH
            else -> Transport.OTHER
        }
    }

    private companion object {
        /** How long the home screen keeps the fast network up without a conversation. */
        const val WARM_MS = 20_000L
        /** After a conversation: long enough for the home screen to come back and take it over. */
        const val LINGER_MS = 3_000L
    }
}
