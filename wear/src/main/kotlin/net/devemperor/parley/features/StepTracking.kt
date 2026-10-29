package net.devemperor.parley.features

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.core.content.edit
import androidx.health.services.client.HealthServices
import androidx.health.services.client.PassiveListenerService
import androidx.health.services.client.clearPassiveListenerService
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.PassiveListenerConfig
import androidx.health.services.client.getCapabilities
import androidx.health.services.client.setPassiveListenerService
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.devemperor.parley.Diagnostics
import net.devemperor.parley.core.tools.WatchFeature
import net.devemperor.parley.parley
import kotlin.math.abs

/**
 * Keeps Health Services counting today's steps. On a Galaxy Watch it counts only while some app is
 * registered for them: registered just for the moment of a question, Parley got the few steps taken
 * meanwhile (6 against Samsung Health's 7,800 on 2026-10-29). A lasting registration keeps it counting
 * all day and starting over at midnight. Health Services then hands the running count to
 * [StepTrackingService] now and then, which drops it; the count is read only when the user asks
 * (WatchSensors.stepsToday). A goal alone, without the data, it refuses ("must be tracked").
 *
 * Registered while the steps feature is on and allowed, withdrawn otherwise. A restart of the watch
 * ends every registration, so it is made again after one ([StepTrackingBootReceiver]) and whenever
 * Parley finds it was made before the last start or update.
 */
object StepTracking {
    private val lock = Mutex()

    /** Registers or withdraws the goal to match the steps feature; does nothing when it already does. */
    suspend fun sync(context: Context) = lock.withLock {
        val app = context.parley
        val wanted = WatchFeature.STEPS in app.settings.current().features && WatchFeatures.granted(context, WatchFeature.STEPS)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = signature(context)
        val registered = prefs.getString(KEY_REGISTERED, null)
        if (wanted == (registered != null && sameStart(registered, current))) return@withLock
        val client = HealthServices.getClient(context).passiveMonitoringClient
        runCatching {
            if (wanted) {
                if (DataType.STEPS_DAILY !in client.getCapabilities().supportedDataTypesPassiveMonitoring) return@withLock
                client.setPassiveListenerService(StepTrackingService::class.java, CONFIG)
                prefs.edit { putString(KEY_REGISTERED, current) }
            } else {
                client.clearPassiveListenerService()
                prefs.edit { remove(KEY_REGISTERED) }
            }
            if (Diagnostics.enabled) Log.d("ParleyEvent", "step tracking ${if (wanted) "registered" else "withdrawn"}")
        }.onFailure { Log.w("Parley", "Step tracking: ${it.message}") }
    }

    /** Health Services dropped the registration (the permission was taken back). */
    internal fun forget(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { remove(KEY_REGISTERED) }
    }

    /** When the watch started and when Parley was last installed: either ends a registration. */
    private fun signature(context: Context): String {
        val boot = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        val installed = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime }.getOrDefault(0L)
        return "$boot/$installed"
    }

    /** The boot time drifts with the wall clock by a few milliseconds between readings. */
    private fun sameStart(a: String, b: String): Boolean {
        val (bootA, installedA) = a.split('/').map { it.toLongOrNull() ?: return false }
        val (bootB, installedB) = b.split('/').map { it.toLongOrNull() ?: return false }
        return installedA == installedB && abs(bootA - bootB) < BOOT_TOLERANCE_MS
    }

    private val CONFIG = PassiveListenerConfig.Builder().setDataTypes(setOf(DataType.STEPS_DAILY)).build()

    private const val BOOT_TOLERANCE_MS = 60_000L
    private const val PREFS = "step_tracking"
    private const val KEY_REGISTERED = "registered"
}

/**
 * Health Services needs a service for a lasting registration. What it delivers here is dropped: the
 * day's count is asked for when needed.
 */
class StepTrackingService : PassiveListenerService() {
    override fun onPermissionLost() = StepTracking.forget(this)
}

/** Registrations do not survive a restart of the watch. */
class StepTrackingBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        context.parley.appScope.launch {
            try {
                StepTracking.sync(context)
            } finally {
                pending.finish()
            }
        }
    }
}
