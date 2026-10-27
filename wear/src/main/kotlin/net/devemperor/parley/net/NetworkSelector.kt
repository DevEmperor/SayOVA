package net.devemperor.parley.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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

enum class Transport(val label: String) {
    WIFI("Wi-Fi"),
    CELLULAR("LTE"),
    BLUETOOTH("via phone"),
    OTHER("Online"),
    NONE("Offline"),
}

/**
 * Streaming audio needs more bandwidth than the Bluetooth link to the phone reliably gives, and Wear
 * OS prefers that link to save battery. For the length of a conversation Parley therefore asks for
 * Wi-Fi or LTE and routes its sockets through it, falling back to whatever is there if neither comes
 * up in time. The request is dropped again when the conversation ends, so the radio can sleep.
 */
class NetworkSelector(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    private val _fastNetwork = MutableStateFlow<Network?>(null)
    private val _transport = MutableStateFlow(Transport.NONE)
    val transport: StateFlow<Transport> = _transport.asStateFlow()

    private var callback: ConnectivityManager.NetworkCallback? = null

    /** Requests a fast network and waits up to [timeoutMs] for it. */
    suspend fun acquire(timeoutMs: Long = 4_000) {
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
        withTimeoutOrNull(timeoutMs) { _fastNetwork.first { it != null } }
        updateTransport()
    }

    fun release() {
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
}
