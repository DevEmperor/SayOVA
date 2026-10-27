package net.devemperor.parley

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.devemperor.parley.data.HistoryStore
import net.devemperor.parley.data.KeyVault
import net.devemperor.parley.data.SettingsRepository
import net.devemperor.parley.net.NetworkSelector
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class ParleyApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val settings by lazy { SettingsRepository(this) }
    val keys by lazy { KeyVault(this) }
    val history by lazy { HistoryStore(this) }
    val network by lazy { NetworkSelector(this) }

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            // Keeps NAT mappings on the way alive during long pauses in a conversation.
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            history.load()
            history.prune(settings.current().historyDays)
        }
    }
}

val Context.parley: ParleyApp get() = applicationContext as ParleyApp
