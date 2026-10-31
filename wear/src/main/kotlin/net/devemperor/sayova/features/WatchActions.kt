package net.devemperor.sayova.features

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import android.view.KeyEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.devemperor.sayova.core.phone.PhoneLinks
import net.devemperor.sayova.core.weather.MetNorway
import net.devemperor.sayova.ui.components.openOnPhone
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * The watch's abilities beyond its sensors (see WatchSensors): media keys, the next alarm, contacts and
 * calls, links opened on the phone, opening apps, the weather from MET Norway, the barometer and the
 * notifications. Callers check
 * the permissions first (see WatchFeatures). Failures are [WatchSensors.Unavailable] with a reason the
 * model can pass on.
 */
@SuppressLint("MissingPermission")
class WatchActions(private val context: Context, private val http: OkHttpClient) {

    private fun unavailable(reason: String): Nothing = throw WatchSensors.Unavailable(reason)

    // ── Music ───────────────────────────────────────────────────────────────────────────────────

    data class NowPlaying(val title: String?, val artist: String?, val app: String?, val playing: Boolean)

    /** Presses the media key, as headphones would: whichever app plays on the watch follows it. */
    fun mediaKey(action: String) {
        val code = when (action.lowercase()) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> unavailable("Unknown action \"$action\"; use play, pause, next or previous.")
        }
        val audio = context.getSystemService(AudioManager::class.java)
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    /** What plays now; only with notification access, which media sessions require. */
    fun nowPlaying(): NowPlaying? {
        if (!NotificationReader.allowed(context)) return null
        val sessions = runCatching {
            context.getSystemService(MediaSessionManager::class.java).getActiveSessions(NotificationReader.component(context))
        }.getOrNull().orEmpty()
        val session = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: sessions.firstOrNull() ?: return null
        val metadata = session.metadata
        return NowPlaying(
            title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE),
            artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST),
            app = appLabel(session.packageName),
            playing = session.playbackState?.state == PlaybackState.STATE_PLAYING,
        )
    }

    // ── Alarm ───────────────────────────────────────────────────────────────────────────────────

    /** The next alarm clock time as epoch milliseconds, and the app that set it; null when none is set. */
    fun nextAlarm(): Pair<Long, String?>? {
        val alarm = context.getSystemService(AlarmManager::class.java).nextAlarmClock ?: return null
        return alarm.triggerTime to alarm.showIntent?.creatorPackage?.let(::appLabel)
    }

    // ── Contacts and calls ──────────────────────────────────────────────────────────────────────

    data class Contact(val name: String, val number: String, val type: String?)

    /** Contacts whose name contains every word of [query], accents and case ignored; at most eight numbers. */
    suspend fun findContacts(query: String): List<Contact> = withContext(Dispatchers.IO) {
        val words = fold(query).split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) unavailable("The name is empty.")
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE,
            ContactsContract.CommonDataKinds.Phone.LABEL,
        )
        val found = mutableListOf<Contact>()
        context.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(0) ?: continue
                if (words.all { it in fold(name) }) {
                    val type = ContactsContract.CommonDataKinds.Phone.getTypeLabel(context.resources, cursor.getInt(2), cursor.getString(3)).toString()
                    found += Contact(name, cursor.getString(1) ?: continue, type)
                }
            }
        }
        found.distinctBy { it.name to it.number.filter(Char::isDigit) }.take(MAX_CONTACTS)
    }

    /** Whether this watch can place calls itself (LTE, or calls through the phone the system offers). */
    fun canCall(): Boolean = context.packageManager.hasSystemFeature(
        if (android.os.Build.VERSION.SDK_INT >= 33) PackageManager.FEATURE_TELEPHONY_CALLING else PackageManager.FEATURE_TELEPHONY,
    )

    /** Places the call through the system: no activity of Sayova's needs to be on screen for it. */
    fun call(number: String) {
        context.getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", number, null), Bundle())
    }

    // ── On the phone ────────────────────────────────────────────────────────────────────────────

    /** Opens [link] on the paired phone; false when no phone took it (none connected, or no answer in time). */
    suspend fun openOnPhone(link: String): Boolean = withTimeoutOrNull(PHONE_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation -> openOnPhone(context, link) { continuation.resume(it) } }
    } ?: false

    /**
     * [number] as WhatsApp's links want it: with its country code as written, or else completed for
     * the country the watch is in (from the mobile network, the SIM, then the time zone, which the
     * watch takes from the phone). Null when neither tells.
     */
    fun internationalNumber(number: String): String? {
        PhoneLinks.internationalDigits(number)?.let { return it }
        val telephony = context.getSystemService(TelephonyManager::class.java)
        val countries = listOfNotNull(
            telephony?.networkCountryIso,
            telephony?.simCountryIso,
            runCatching { android.icu.util.TimeZone.getRegion(TimeZone.getDefault().id) }.getOrNull(),
        )
        return countries.filter { it.length == 2 }.firstNotNullOfOrNull { country ->
            PhoneNumberUtils.formatNumberToE164(PhoneLinks.dialable(number), country.uppercase())?.let(PhoneLinks::internationalDigits)
        }
    }

    // ── Apps ────────────────────────────────────────────────────────────────────────────────────

    data class App(val label: String, val intent: Intent)

    fun launchableApps(): List<App> {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcher, 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .mapNotNull { info ->
                val intent = pm.getLaunchIntentForPackage(info.activityInfo.packageName) ?: return@mapNotNull null
                App(info.loadLabel(pm).toString(), intent)
            }
            .distinctBy { it.label }
            .sortedBy { it.label.lowercase() }
    }

    /** The app the user means: the same name first, then one that starts with it, then one containing it. */
    fun findApp(name: String, apps: List<App>): App? {
        val wanted = fold(name).replace(" ", "")
        if (wanted.isEmpty()) return null
        val keyed = apps.map { fold(it.label).replace(" ", "") to it }
        return keyed.firstOrNull { it.first == wanted }?.second
            ?: keyed.firstOrNull { it.first.startsWith(wanted) }?.second
            ?: keyed.firstOrNull { wanted in it.first }?.second
            ?: keyed.firstOrNull { it.first in wanted && it.first.length >= 4 }?.second
    }

    // Another app, opened as the launcher would: it belongs in its own task.
    @SuppressLint("WearRecents")
    fun open(app: App) {
        context.startActivity(Intent(app.intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ── Weather ─────────────────────────────────────────────────────────────────────────────────

    /** The forecast for a place from MET Norway; the request carries only its rounded coordinates. */
    suspend fun weather(latitude: Double, longitude: Double): MetNorway.Report = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(MetNorway.url(latitude, longitude)).header("User-Agent", MetNorway.USER_AGENT).build()
        val body = runCatching {
            http.newBuilder().callTimeout(WEATHER_TIMEOUT_S, TimeUnit.SECONDS).build().newCall(request).execute().use { response ->
                if (!response.isSuccessful) unavailable("The weather service answered ${response.code}.")
                response.body.string()
            }
        }.getOrElse { if (it is WatchSensors.Unavailable) throw it else unavailable("The weather service could not be reached.") }
        MetNorway.parse(body, ZoneId.systemDefault(), Instant.now()) ?: unavailable("The weather service sent no forecast.")
    }

    // ── Air pressure ────────────────────────────────────────────────────────────────────────────

    /** One reading of the barometer in hPa. */
    suspend fun airPressure(): Float {
        val sensors = context.getSystemService(SensorManager::class.java)
        val barometer = sensors.getDefaultSensor(Sensor.TYPE_PRESSURE) ?: unavailable("This watch has no barometer.")
        return withTimeoutOrNull(SENSOR_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        sensors.unregisterListener(this)
                        event.values.firstOrNull()?.let { continuation.resume(it) }
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                }
                sensors.registerListener(listener, barometer, SensorManager.SENSOR_DELAY_NORMAL)
                continuation.invokeOnCancellation { sensors.unregisterListener(listener) }
            }
        } ?: unavailable("The barometer gave no reading.")
    }

    /** A position the watch already knows from the last half hour, for the barometer: no waiting for a fix. */
    fun recentLocation(): Location? {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val manager = context.getSystemService(LocationManager::class.java)
        return manager.getProviders(true).mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .filter { TimeUnit.NANOSECONDS.toMinutes(SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) <= 30 }
            .maxByOrNull { it.elapsedRealtimeNanos }
    }

    // ── Notifications ───────────────────────────────────────────────────────────────────────────

    data class Note(val app: String?, val title: String?, val text: String?, val postedAt: Long)

    /** The notifications a person would see, newest first: no ongoing ones (music, calls), none of Sayova's. */
    suspend fun notifications(): List<Note>? {
        val all = NotificationReader.current(context) ?: return null
        return all.asSequence()
            .filter { !it.isOngoing && it.packageName != context.packageName }
            .filter { it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
            .sortedByDescending { it.postTime }
            .map { sbn ->
                val extras = sbn.notification.extras
                Note(
                    app = appLabel(sbn.packageName),
                    title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
                    text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString(),
                    postedAt = sbn.postTime,
                )
            }
            .take(MAX_NOTIFICATIONS)
            .toList()
    }

    private fun appLabel(packageName: String): String? = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrNull()

    /** Lower case without accents, so "Lena Müller" is found as "lena muller" and "Lena". */
    private fun fold(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").replace(Regex("[^\\p{L}\\p{N} ]"), " ").trim()

    private companion object {
        const val MAX_CONTACTS = 8
        const val MAX_NOTIFICATIONS = 10
        const val WEATHER_TIMEOUT_S = 15L
        const val SENSOR_TIMEOUT_MS = 3_000L
        const val PHONE_TIMEOUT_MS = 15_000L
    }
}
