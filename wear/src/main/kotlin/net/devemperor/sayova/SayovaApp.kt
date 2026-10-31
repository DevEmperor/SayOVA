package net.devemperor.sayova

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import net.devemperor.sayova.core.tools.WatchFeature
import net.devemperor.sayova.core.usage.Usage
import net.devemperor.sayova.data.HistoryStore
import net.devemperor.sayova.data.KeyVault
import net.devemperor.sayova.data.MemoryStore
import net.devemperor.sayova.data.SettingsRepository
import net.devemperor.sayova.data.UsageStore
import net.devemperor.sayova.features.StepTracking
import net.devemperor.sayova.net.NetworkSelector
import okhttp3.OkHttpClient
import java.time.YearMonth
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class SayovaApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val settings by lazy { SettingsRepository(this) }
    val keys by lazy { KeyVault(this) }
    val history by lazy { HistoryStore(this) }
    val usage by lazy { UsageStore(this) }
    val memory by lazy { MemoryStore(this) }
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
            // The usage before the history: a conversation started with the app waits for it (the
            // budget), and a history kept forever takes a while to read. Only a watch without
            // statistics yet needs the history first, to start them from.
            var historyLoaded = false
            usage.load(seed = {
                history.load()
                historyLoaded = true
                var days = Usage.fromConversations(history.conversations.value, ZoneId.systemDefault())
                // Cost counted in the month before the statistics, beyond what the history shows.
                settings.takeLegacyMonthCost()?.let { (month, usd) ->
                    val start = YearMonth.parse(month).atDay(1)
                    val known = Usage.month(days, YearMonth.parse(month)).costUsd
                    if (usd - known > 0.0005) days = Usage.add(days, start, usd - known, 0, conversations = 0)
                }
                days
            })
            if (!historyLoaded) history.load()
            memory.load()
            history.prune(settings.current().historyDays)
        }
        // Keeps Health Services counting steps while the feature is on, from the first start on.
        appScope.launch {
            settings.settings.map { WatchFeature.STEPS in it.features }.distinctUntilChanged().collect { StepTracking.sync(this@SayovaApp) }
        }
    }
}

val Context.sayova: SayovaApp get() = applicationContext as SayovaApp
