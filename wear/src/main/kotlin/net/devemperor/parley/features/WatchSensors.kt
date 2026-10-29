package net.devemperor.parley.features

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import android.provider.CalendarContract
import android.util.Log
import androidx.core.net.toUri
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.PassiveListenerCallback
import androidx.health.services.client.clearPassiveListenerCallback
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DataTypeAvailability
import androidx.health.services.client.data.DeltaDataType
import androidx.health.services.client.data.PassiveListenerConfig
import androidx.health.services.client.flush
import androidx.health.services.client.getCapabilities
import androidx.health.services.client.unregisterMeasureCallback
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.devemperor.parley.Diagnostics
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Reads the watch's own sensors and data for the model, each only while a conversation asks for it:
 * nothing is recorded in the background. Callers check the permission first (see WatchFeatures).
 * Failures are [Unavailable] with a reason the model can pass on.
 */
@SuppressLint("MissingPermission")
class WatchSensors(private val context: Context) {

    class Unavailable(reason: String) : Exception(reason)

    data class Place(val latitude: Double, val longitude: Double, val accuracyM: Float?, val name: String?, val precise: Boolean, val ageMinutes: Long)

    data class Event(val title: String, val begin: Long, val end: Long, val allDay: Boolean, val location: String?)

    // ── Location ────────────────────────────────────────────────────────────────────────────────

    suspend fun location(): Place {
        val manager = context.getSystemService(LocationManager::class.java)
        if (!manager.isLocationEnabled) throw Unavailable("Location is turned off in the watch's settings.")
        // The fused provider also uses the phone's location when the watch has no fix of its own; its
        // balanced mode may find nothing without a network fix, so the watch's GPS comes after it.
        val providers = listOfNotNull(
            if (Build.VERSION.SDK_INT >= 31) LocationManager.FUSED_PROVIDER else null,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
        ).filter { manager.isProviderEnabled(it) }
        if (providers.isEmpty()) throw Unavailable("This watch has no source for its location.")
        val known = providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.elapsedRealtimeNanos }
        val location = known?.takeIf { ageMinutes(it) <= RECENT_MINUTES }
            ?: current(manager, providers.first(), FIRST_TRY_MS)
            ?: providers.drop(1).lastOrNull()?.let { current(manager, it, SECOND_TRY_MS) }
            ?: known?.takeIf { ageMinutes(it) <= MAX_LAST_KNOWN_MINUTES }
            ?: throw Unavailable("The watch could not find its location just now.")
        val precise = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return Place(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyM = if (location.hasAccuracy()) location.accuracy else null,
            name = placeName(location),
            precise = precise,
            ageMinutes = ageMinutes(location),
        )
    }

    private suspend fun current(manager: LocationManager, provider: String, timeoutMs: Long): Location? = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            manager.getCurrentLocation(provider, signal, context.mainExecutor) { continuation.resume(it) }
            continuation.invokeOnCancellation { signal.cancel() }
        }
    }

    private fun ageMinutes(location: Location): Long =
        TimeUnit.NANOSECONDS.toMinutes(SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos)

    /** A place's coordinates by its name, through the watch's geocoder; null without one or when unknown. */
    suspend fun coordinates(place: String): Pair<Double, Double>? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(context, Locale.getDefault())
        return withTimeoutOrNull(GEOCODER_TIMEOUT_MS) {
            val address: Address? = if (Build.VERSION.SDK_INT >= 33) {
                suspendCancellableCoroutine { continuation ->
                    geocoder.getFromLocationName(place, 1, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) = continuation.resume(addresses.firstOrNull())
                        override fun onError(errorMessage: String?) = continuation.resume(null)
                    })
                }
            } else {
                withContext(Dispatchers.IO) {
                    @Suppress("DEPRECATION")
                    runCatching { geocoder.getFromLocationName(place, 1)?.firstOrNull() }.getOrNull()
                }
            }
            address?.takeIf { it.hasLatitude() && it.hasLongitude() }?.let { it.latitude to it.longitude }
        }
    }

    /** The town, and the district when known; null on watches without a geocoder, which is common. */
    private suspend fun placeName(location: Location): String? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(context, Locale.getDefault())
        return withTimeoutOrNull(GEOCODER_TIMEOUT_MS) {
            val address: Address? = if (Build.VERSION.SDK_INT >= 33) {
                suspendCancellableCoroutine { continuation ->
                    geocoder.getFromLocation(location.latitude, location.longitude, 1, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) = continuation.resume(addresses.firstOrNull())
                        override fun onError(errorMessage: String?) = continuation.resume(null)
                    })
                }
            } else {
                withContext(Dispatchers.IO) {
                    @Suppress("DEPRECATION")
                    runCatching { geocoder.getFromLocation(location.latitude, location.longitude, 1)?.firstOrNull() }.getOrNull()
                }
            }
            address?.let { listOfNotNull(it.subLocality, it.locality ?: it.subAdminArea, it.countryName).distinct().joinToString(", ") }
                ?.takeIf { it.isNotEmpty() }
        }
    }

    // ── Heart rate ──────────────────────────────────────────────────────────────────────────────

    /** The median of the first few readings: the very first ones after the sensor starts jump about. */
    suspend fun heartRate(): Int {
        val client = HealthServices.getClient(context).measureClient
        if (DataType.HEART_RATE_BPM !in client.getCapabilities().supportedDataTypesMeasure) {
            throw Unavailable("This watch cannot measure the heart rate.")
        }
        val readings = Channel<Double>(Channel.UNLIMITED)
        var offBody = false
        val callback = object : MeasureCallback {
            override fun onAvailabilityChanged(dataType: DeltaDataType<*, *>, availability: Availability) {
                if (availability == DataTypeAvailability.UNAVAILABLE_DEVICE_OFF_BODY) {
                    offBody = true
                    readings.close()
                }
            }

            override fun onDataReceived(data: DataPointContainer) {
                data.getData(DataType.HEART_RATE_BPM).forEach { if (it.value > 0) readings.trySend(it.value) }
            }
        }
        client.registerMeasureCallback(DataType.HEART_RATE_BPM, callback)
        try {
            val values = mutableListOf<Double>()
            withTimeoutOrNull(HEART_RATE_TIMEOUT_MS) {
                for (value in readings) {
                    values += value
                    if (values.size >= HEART_RATE_READINGS) break
                }
            }
            if (offBody) throw Unavailable("The watch is not on the wrist.")
            if (values.isEmpty()) throw Unavailable("The watch could not measure the heart rate. It should sit snugly on the wrist while the user holds still.")
            return values.sorted()[values.size / 2].roundToInt()
        } finally {
            withContext(NonCancellable) { runCatching { client.unregisterMeasureCallback(DataType.HEART_RATE_BPM, callback) } }
        }
    }

    // ── Steps ───────────────────────────────────────────────────────────────────────────────────

    /**
     * Today's steps as Health Services counts them for the whole watch: listening briefly and asking
     * it to flush hands over the current daily total.
     */
    suspend fun stepsToday(): Long {
        val client = HealthServices.getClient(context).passiveMonitoringClient
        if (DataType.STEPS_DAILY !in client.getCapabilities().supportedDataTypesPassiveMonitoring) {
            throw Unavailable("This watch does not count steps.")
        }
        val steps = CompletableDeferred<Long>()
        val callback = object : PassiveListenerCallback {
            override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
                val points = dataPoints.getData(DataType.STEPS_DAILY)
                if (Diagnostics.enabled) {
                    val boot = Instant.ofEpochMilli(System.currentTimeMillis() - SystemClock.elapsedRealtime())
                    points.forEach { Log.d("ParleyEvent", "steps daily ${it.value} from ${it.getStartInstant(boot)} to ${it.getEndInstant(boot)}") }
                    logStepCounter()
                }
                points.maxByOrNull { it.endDurationFromBoot }?.let { steps.complete(it.value) }
            }
        }
        client.setPassiveListenerCallback(PassiveListenerConfig.Builder().setDataTypes(setOf(DataType.STEPS_DAILY)).build(), callback)
        try {
            client.flush()
            return withTimeoutOrNull(STEPS_TIMEOUT_MS) { steps.await() } ?: throw Unavailable("The watch has no step count for today yet.")
        } finally {
            withContext(NonCancellable) { runCatching { client.clearPassiveListenerCallback() } }
        }
    }

    /** For the diagnosis of a wrong count: the raw step sensor, counting since the watch started. */
    private fun logStepCounter() {
        val sensors = context.getSystemService(SensorManager::class.java) ?: return
        val counter = sensors.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        if (counter == null) {
            Log.d("ParleyEvent", "no step counter sensor")
            return
        }
        sensors.registerListener(object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                Log.d("ParleyEvent", "step counter ${event.values.firstOrNull()} since boot")
                sensors.unregisterListener(this)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }, counter, SensorManager.SENSOR_DELAY_NORMAL)
    }

    // ── Calendar ────────────────────────────────────────────────────────────────────────────────

    /**
     * Events from now to the end of the [days]th day. Watches keep the phone's calendar in the system
     * calendar or, on Wear OS with Google's phone app, in a provider of their own; both are read.
     */
    suspend fun calendar(days: Int): List<Event> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val until = LocalDate.now(zone).plusDays(days.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
        (instances(CalendarContract.Instances.CONTENT_URI, now, until) + instances(WEARABLE_INSTANCES, now, until))
            .filter { it.end > now }
            .distinctBy { it.title to it.begin }
            .sortedBy { it.begin }
            .take(MAX_EVENTS)
    }

    private fun instances(base: Uri, from: Long, until: Long): List<Event> = runCatching {
        val uri = base.buildUpon().also {
            ContentUris.appendId(it, from)
            ContentUris.appendId(it, until)
        }.build()
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
        )
        context.contentResolver.query(uri, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        Event(
                            title = cursor.getString(0)?.trim().orEmpty().ifEmpty { "(untitled)" },
                            begin = cursor.getLong(1),
                            end = cursor.getLong(2),
                            allDay = cursor.getInt(3) == 1,
                            location = cursor.getString(4)?.trim()?.takeIf { it.isNotEmpty() },
                        ),
                    )
                }
            }
        }.orEmpty()
    }.getOrDefault(emptyList())

    private companion object {
        /** A position this recent answers at once; older ones only when nothing fresh comes. */
        const val RECENT_MINUTES = 5L
        const val FIRST_TRY_MS = 10_000L
        const val SECOND_TRY_MS = 15_000L
        const val MAX_LAST_KNOWN_MINUTES = 30L
        const val GEOCODER_TIMEOUT_MS = 5_000L
        const val HEART_RATE_TIMEOUT_MS = 30_000L
        const val HEART_RATE_READINGS = 5
        const val STEPS_TIMEOUT_MS = 10_000L
        const val MAX_EVENTS = 20

        /** WearableCalendarContract.Instances, without the old wearable support library it lives in. */
        val WEARABLE_INSTANCES: Uri = "content://com.google.android.wearable.provider.calendar/instances/when".toUri()
    }
}
