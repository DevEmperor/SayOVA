package net.devemperor.talova.session

import android.content.Context
import android.content.Intent
import android.hardware.SensorManager
import android.os.BatteryManager
import android.provider.AlarmClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.devemperor.talova.Diagnostics
import net.devemperor.talova.R
import net.devemperor.talova.core.memory.Memory
import net.devemperor.talova.core.phone.PhoneLinks
import net.devemperor.talova.core.provider.openai.OpenAiWebSearch
import net.devemperor.talova.core.session.VoiceEvent
import net.devemperor.talova.core.tools.TalovaTools
import net.devemperor.talova.core.tools.WatchFeature
import net.devemperor.talova.core.weather.MetNorway
import net.devemperor.talova.data.MemoryStore
import net.devemperor.talova.features.PermissionBroker
import net.devemperor.talova.features.WatchActions
import net.devemperor.talova.features.WatchFeatures
import net.devemperor.talova.features.WatchSensors
import okhttp3.OkHttpClient
import net.devemperor.talova.talova
import net.devemperor.talova.ui.components.eventLink
import net.devemperor.talova.ui.components.shareLink
import net.devemperor.talova.ui.labelRes
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Carries out the model's tool calls on the watch. Every call answers, also when it fails, so the
 * model can tell the user what happened instead of waiting.
 */
class ToolRunner(
    private val context: Context,
    private val webSearch: (suspend (String) -> String)?,
    /** Null while the memory is turned off; the model is then offered no memory tools either. */
    private val memory: MemoryStore?,
    /** The watch features the user left on; only these were offered to the model. */
    private val features: Set<WatchFeature> = emptySet(),
    /** Called when the user allowed a feature mid-conversation (the service widens its type). */
    private val onAllowed: () -> Unit = {},
    /** The conversation's network, for the weather. */
    http: OkHttpClient = OkHttpClient(),
) {
    private val sensors = WatchSensors(context)
    private val actions = WatchActions(context, http)

    /**
     * [note] is shown in the transcript as what Talova did, e.g. "Timer · 5 min"; [notice] is the
     * shorter text under the orb, the note itself when not given. [afterEnd] runs once the
     * conversation has ended (a phone call must not share the microphone with it).
     */
    data class Outcome(
        val result: JsonObject,
        val note: String? = null,
        val notice: String? = null,
        val savedNote: String? = null,
        val endsConversation: Boolean = false,
        val afterEnd: (() -> Unit)? = null,
    )

    /** [onGranted] runs when the user allows a watch feature during the call, before it is used. */
    suspend fun run(call: VoiceEvent.ToolCall, onGranted: () -> Unit = {}): Outcome = when (call.name) {
        TalovaTools.DEVICE_STATUS -> deviceStatus()
        TalovaTools.SET_TIMER -> setTimer(call.arguments)
        TalovaTools.SET_ALARM -> setAlarm(call.arguments)
        TalovaTools.SHOW_CLOCK -> showClock(call.arguments)
        TalovaTools.SAVE_NOTE -> saveNote(call.arguments)
        TalovaTools.END_CONVERSATION -> Outcome(ok(), endsConversation = true)
        TalovaTools.SEARCH_WEB -> search(call.arguments)
        TalovaTools.REMEMBER_FACT -> remember(call.arguments)
        TalovaTools.FORGET_FACT -> forget(call.arguments)
        else -> WatchFeature.forTool(call.name)?.let { watchFeature(it, call, onGranted) } ?: Outcome(error("Unknown tool ${call.name}"))
    }

    /** A watch feature: asks for its permission the first time, then reads the sensor or data. */
    private suspend fun watchFeature(feature: WatchFeature, call: VoiceEvent.ToolCall, onGranted: () -> Unit): Outcome {
        val args = call.arguments
        val name = context.getString(feature.labelRes())
        val where = "Talova's settings under ${setting(R.string.features_title)}"
        if (feature !in features) return Outcome(error("The user turned this off in $where."))
        if (feature == WatchFeature.NOTIFICATIONS && !WatchFeatures.granted(context, feature)) {
            // No dialog can grant it: a switch in the system settings does, where the watch has one.
            return Outcome(
                error("Notification access is off. The user switches it on for Talova in the watch's system settings; $where tells how."),
                note = context.getString(R.string.note_not_allowed, name),
            )
        }
        if (!WatchFeatures.granted(context, feature)) {
            context.talova.settings.update { it.copy(featuresAsked = it.featuresAsked + feature) }
            when (PermissionBroker.request(feature)) {
                PermissionBroker.Answer.GRANTED -> {
                    onAllowed()
                    onGranted()
                }
                PermissionBroker.Answer.DENIED -> return Outcome(
                    error("The user did not allow it. They can allow it later in $where."),
                    note = context.getString(R.string.note_not_allowed, name),
                )
                PermissionBroker.Answer.NOT_ON_SCREEN -> return Outcome(
                    error("Talova needs the user's permission first, but the watch screen is off. Ask the user to look at the watch and ask again, or to allow it in $where."),
                )
            }
        }
        return try {
            when (feature) {
                WatchFeature.LOCATION -> location()
                WatchFeature.WEATHER -> weather(args, where, onGranted)
                WatchFeature.HEART_RATE -> heartRate()
                WatchFeature.STEPS -> steps()
                WatchFeature.PRESSURE -> airPressure()
                WatchFeature.CALENDAR -> calendar(args)
                WatchFeature.ALARM -> nextAlarm()
                WatchFeature.MUSIC -> media(args)
                WatchFeature.CALLS -> if (call.name == TalovaTools.FIND_CONTACT) findContact(args) else callPhone(args)
                WatchFeature.APPS -> openApp(args)
                WatchFeature.NOTIFICATIONS -> notifications()
                WatchFeature.PHONE -> prepareOnPhone(call.name, args)
            }
        } catch (e: WatchSensors.Unavailable) {
            Outcome(error(e.message.orEmpty()))
        } catch (e: SecurityException) {
            Outcome(error("The watch did not allow access: ${e.message}"))
        } catch (e: Exception) {
            Outcome(error("It did not work: ${e.message}"))
        }
    }

    private suspend fun location(): Outcome {
        val place = sensors.location()
        // Coarse permission gets a position blurred to a few kilometres; no more digits than it has.
        val digits = if (place.precise) 4 else 2
        return Outcome(
            buildJsonObject {
                put("latitude", "%.${digits}f".format(Locale.ROOT, place.latitude).toDouble())
                put("longitude", "%.${digits}f".format(Locale.ROOT, place.longitude).toDouble())
                place.accuracyM?.let { put("accuracy_m", it.toInt()) }
                place.name?.let { put("place", it) }
                put("precise", place.precise)
                if (place.ageMinutes > 1) put("age_minutes", place.ageMinutes)
            },
            note = place.name?.let { context.getString(R.string.note_location, it) } ?: context.getString(R.string.note_location_used),
        )
    }

    private suspend fun heartRate(): Outcome {
        val bpm = sensors.heartRate()
        return Outcome(buildJsonObject { put("bpm", bpm) }, note = context.getString(R.string.note_heart_rate, bpm))
    }

    private suspend fun steps(): Outcome {
        val steps = sensors.stepsToday()
        val formatted = NumberFormat.getIntegerInstance(context.resources.configuration.locales[0]).format(steps)
        return Outcome(buildJsonObject { put("steps_today", steps) }, note = context.getString(R.string.note_steps, formatted))
    }

    private suspend fun calendar(args: JsonObject): Outcome {
        val days = (args["days"]?.jsonPrimitive?.intOrNull ?: 1).coerceIn(1, 14)
        val events = sensors.calendar(days)
        val zone = ZoneId.systemDefault()
        fun local(millis: Long, allDay: Boolean): String {
            // All-day events are stored at midnight UTC.
            val instant = Instant.ofEpochMilli(millis)
            return if (allDay) instant.atZone(ZoneId.of("UTC")).toLocalDate().toString() else instant.atZone(zone).toLocalDateTime().withSecond(0).withNano(0).toString()
        }
        return Outcome(
            buildJsonObject {
                put("days", days)
                put("count", events.size)
                put("events", buildJsonArray {
                    events.forEach { event ->
                        add(buildJsonObject {
                            put("title", event.title.take(120))
                            put("start", local(event.begin, event.allDay))
                            put("end", local(event.end, event.allDay))
                            put("all_day", event.allDay)
                            event.location?.let { put("location", it.take(120)) }
                        })
                    }
                })
            },
            note = context.resources.getQuantityString(R.plurals.note_calendar, events.size, events.size),
        )
    }

    /**
     * Where the watch is, or the place the model named by its coordinates. The location is asked for
     * only for "here", so the weather elsewhere works without it.
     */
    private suspend fun weather(args: JsonObject, where: String, onGranted: () -> Unit): Outcome {
        val latitude = args["latitude"]?.jsonPrimitive?.doubleOrNull
        val longitude = args["longitude"]?.jsonPrimitive?.doubleOrNull
        var place = args["place"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        val (lat, lon) = if (latitude != null && longitude != null) {
            latitude to longitude
        } else if (place != null) {
            // A place named without coordinates must not get the weather of where the watch is.
            sensors.coordinates(place)
                ?: return Outcome(error("Unknown place \"$place\" on this watch: call get_weather again with its latitude and longitude."))
        } else {
            if (WatchFeature.LOCATION !in features) return Outcome(error("The watch's location is turned off in $where. Ask the user for a place."))
            if (!WatchFeatures.granted(context, WatchFeature.LOCATION)) {
                context.talova.settings.update { it.copy(featuresAsked = it.featuresAsked + WatchFeature.LOCATION) }
                if (PermissionBroker.request(WatchFeature.LOCATION) != PermissionBroker.Answer.GRANTED) {
                    return Outcome(error("The user did not allow the location. Ask for a place instead, or they can allow it in $where."))
                }
                onAllowed()
                onGranted()
            }
            val here = sensors.location()
            place = place ?: here.name
            here.latitude to here.longitude
        }
        val report = actions.weather(lat, lon)
        val summary = listOfNotNull("${report.now.temperature.roundToInt()} °C", place).joinToString(", ")
        return Outcome(MetNorway.toJson(report, place), note = context.getString(R.string.note_weather, summary))
    }

    /**
     * The barometer, and the altitude it suggests: from the local sea-level pressure when the watch knows
     * where it is (a quick forecast request), otherwise from the standard atmosphere, which the weather
     * shifts by up to a few hundred metres.
     */
    private suspend fun airPressure(): Outcome {
        val pressure = actions.airPressure()
        val seaLevel = actions.recentLocation()?.let { here ->
            runCatching { actions.weather(here.latitude, here.longitude).seaLevelPressure }.getOrNull()
        }
        val altitude = SensorManager.getAltitude((seaLevel ?: SensorManager.PRESSURE_STANDARD_ATMOSPHERE.toDouble()).toFloat(), pressure)
        return Outcome(
            buildJsonObject {
                put("pressure_hpa", (pressure * 10).roundToInt() / 10.0)
                put("altitude_m", altitude.roundToInt())
                put("altitude_from", if (seaLevel != null) "local sea-level pressure" else "standard atmosphere, may be off by a few hundred metres")
                seaLevel?.let { put("sea_level_pressure_hpa", it) }
            },
            note = context.getString(R.string.note_pressure, pressure.roundToInt()),
        )
    }

    private fun nextAlarm(): Outcome {
        val (at, app) = actions.nextAlarm() ?: return Outcome(buildJsonObject { put("alarm_set", false) }, note = context.getString(R.string.note_no_alarm))
        val time = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
        val locale = context.resources.configuration.locales[0]
        return Outcome(
            buildJsonObject {
                put("alarm_set", true)
                put("rings_at", time.toLocalDateTime().withSecond(0).withNano(0).toString())
                put("in_minutes", (at - System.currentTimeMillis()) / 60_000)
                app?.let { put("app", it) }
            },
            note = context.getString(R.string.note_next_alarm, time.format(DateTimeFormatter.ofPattern("EEE HH:mm", locale))),
        )
    }

    private suspend fun media(args: JsonObject): Outcome {
        val action = args["action"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().lowercase()
        actions.mediaKey(action)
        // The player needs a moment before it reports its new state.
        delay(MEDIA_SETTLE_MS)
        val playing = actions.nowPlaying()
        val note = when (action) {
            "play" -> R.string.note_music_play
            "pause" -> R.string.note_music_pause
            "next" -> R.string.note_music_next
            else -> R.string.note_music_previous
        }
        return Outcome(
            buildJsonObject {
                put("ok", true)
                if (playing != null) {
                    playing.title?.let { put("title", it) }
                    playing.artist?.let { put("artist", it) }
                    playing.app?.let { put("app", it) }
                    put("playing", playing.playing)
                } else {
                    put("note", "Sent to whatever plays on the watch; what plays is not known without notification access.")
                }
            },
            note = context.getString(note),
        )
    }

    private suspend fun findContact(args: JsonObject): Outcome {
        val query = args["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val contacts = actions.findContacts(query)
        return Outcome(
            buildJsonObject {
                put("count", contacts.size)
                put("contacts", buildJsonArray {
                    contacts.forEach { contact ->
                        add(buildJsonObject {
                            put("name", contact.name)
                            put("number", contact.number)
                            contact.type?.let { put("type", it) }
                        })
                    }
                })
            },
            note = context.getString(R.string.note_contact, contacts.map { it.name }.distinct().take(2).joinToString(", ").ifEmpty { "–" }),
        )
    }

    private fun callPhone(args: JsonObject): Outcome {
        val number = args["number"]?.jsonPrimitive?.contentOrNull?.filter { it.isDigit() || it in "+*#" }.orEmpty()
        if (number.length < 3) return Outcome(error("No valid number; look it up with find_contact first."))
        if (!actions.canCall()) return Outcome(error("This watch cannot place phone calls itself."))
        val name = args["name"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: number
        return Outcome(
            buildJsonObject {
                put("ok", true)
                put("note", "The call starts when this conversation has ended; say a short goodbye.")
            },
            note = context.getString(R.string.note_calling, name),
            endsConversation = true,
            afterEnd = { actions.call(number) },
        )
    }

    /** Opens the app at once: Talova is on screen then, which Android requires for starting another app. */
    private fun openApp(args: JsonObject): Outcome {
        val name = args["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (!PermissionBroker.hostVisible) return Outcome(error("The watch screen is off, so no app can open. Ask the user to look at the watch and ask again."))
        val apps = actions.launchableApps()
        val app = actions.findApp(name, apps) ?: return Outcome(
            buildJsonObject {
                put("ok", false)
                put("error", "No app called \"$name\". Installed apps:")
                put("apps", buildJsonArray { apps.take(MAX_APP_NAMES).forEach { add(it.label) } })
            },
        )
        actions.open(app)
        return Outcome(buildJsonObject { put("opened", app.label) }, note = context.getString(R.string.note_app_opened, app.label), endsConversation = true)
    }

    private suspend fun notifications(): Outcome {
        val notes = actions.notifications() ?: return Outcome(error("Notification access is off or Android has not connected Talova yet."))
        val zone = ZoneId.systemDefault()
        return Outcome(
            buildJsonObject {
                put("count", notes.size)
                put("notifications", buildJsonArray {
                    notes.forEach { note ->
                        add(buildJsonObject {
                            note.app?.let { put("app", it) }
                            note.title?.let { put("title", it.take(100)) }
                            note.text?.let { put("text", it.take(300)) }
                            put("time", Instant.ofEpochMilli(note.postedAt).atZone(zone).toLocalTime().withSecond(0).withNano(0).toString())
                        })
                    }
                })
            },
            note = context.resources.getQuantityString(R.plurals.note_notifications, notes.size, notes.size),
        )
    }

    /**
     * A message, a calendar event or a route, opened filled in on the paired phone; the user sends,
     * saves or starts it there, so nothing leaves the watch in their name.
     */
    private suspend fun prepareOnPhone(tool: String, args: JsonObject): Outcome {
        fun text(key: String) = args[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        val prepared: Prepared = when (tool) {
            TalovaTools.PREPARE_MESSAGE -> {
                val body = text("text") ?: return Outcome(error("text is empty"))
                val to = text("to")
                val whom = text("name") ?: to
                when (text("app")?.lowercase()) {
                    "whatsapp" -> {
                        val number = to?.let {
                            actions.internationalNumber(it)
                                ?: return Outcome(error("WhatsApp needs the number with its country code, e.g. +49 171 2345678. Ask the user for the country, or leave the number out to pick the contact on the phone."))
                        }
                        Prepared(PhoneLinks.whatsApp(number, body), "WhatsApp", whom)
                    }
                    "sms" -> Prepared(PhoneLinks.sms(to, body), context.getString(R.string.phone_sms), whom)
                    "email" -> {
                        if (to != null && !PhoneLinks.isEmailAddress(to)) {
                            return Outcome(error("\"$to\" is no e-mail address. Leave it out to fill it in on the phone."))
                        }
                        Prepared(PhoneLinks.email(to, text("subject"), body), context.getString(R.string.phone_email), whom)
                    }
                    "other" -> Prepared(shareLink(body), context.getString(R.string.phone_message), text("name"))
                    else -> return Outcome(error("app must be one of: whatsapp, sms, email, other."))
                }
            }
            TalovaTools.PREPARE_EVENT -> {
                val title = text("title") ?: return Outcome(error("title is empty"))
                val start = PhoneLinks.parseWhen(text("start")) ?: return Outcome(error("start must look like 2026-11-02T15:00, or 2026-11-02 for all day."))
                val event = PhoneLinks.event(title, start, PhoneLinks.parseWhen(text("end")), text("place"), text("details"), ZoneId.systemDefault())
                Prepared(eventLink(event.toString()), context.getString(R.string.phone_event), title)
            }
            else -> {
                val destination = text("destination") ?: return Outcome(error("destination is empty"))
                Prepared(PhoneLinks.place(destination), context.getString(R.string.phone_map), destination)
            }
        }
        if (Diagnostics.enabled) android.util.Log.d("TalovaEvent", "on phone: ${prepared.link}")
        if (!actions.openOnPhone(prepared.link)) {
            return Outcome(error("No phone is connected to the watch, so nothing could be prepared there."), note = context.getString(R.string.share_no_phone))
        }
        return Outcome(
            buildJsonObject {
                put("ok", true)
                put("note", "It is open on the user's phone now, for them to send, add or start there.")
            },
            note = prepared.detail?.let { context.getString(R.string.note_on_phone_detail, prepared.what, it) }
                ?: context.getString(R.string.note_on_phone, prepared.what),
        )
    }

    /** A link for the phone, what it opens ("WhatsApp", "Map") and for whom or what, for the transcript. */
    private data class Prepared(val link: String, val what: String, val detail: String?)

    private fun deviceStatus(): Outcome {
        val battery = context.getSystemService(BatteryManager::class.java)
        val now = ZonedDateTime.now()
        return Outcome(buildJsonObject {
            put("local_time", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
            put("time_zone", now.zone.id)
            put("battery_percent", battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1)
            put("charging", battery?.isCharging ?: false)
        })
    }

    private fun setTimer(args: JsonObject): Outcome {
        val seconds = args["seconds"]?.jsonPrimitive?.intOrNull?.takeIf { it in 1..86_400 }
            ?: return Outcome(error("seconds must be between 1 and 86400"))
        val label = args["label"]?.jsonPrimitive?.contentOrNull?.take(60)
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .apply { label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) } }
        return launch(intent, context.getString(R.string.note_timer, formatDuration(seconds)), "No timer app on this watch.")
    }

    private fun setAlarm(args: JsonObject): Outcome {
        val hour = args["hour"]?.jsonPrimitive?.intOrNull?.takeIf { it in 0..23 } ?: return Outcome(error("hour must be 0 to 23"))
        val minute = args["minute"]?.jsonPrimitive?.intOrNull?.takeIf { it in 0..59 } ?: return Outcome(error("minute must be 0 to 59"))
        val label = args["label"]?.jsonPrimitive?.contentOrNull?.take(60)
        val days = weekdays(args["days"]?.jsonPrimitive?.contentOrNull)
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .apply { label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) } }
            .apply { if (days.isNotEmpty()) putExtra(AlarmClock.EXTRA_DAYS, ArrayList(days.map(::calendarDay))) }
        val time = listOfNotNull("%02d:%02d".format(hour, minute), daysText(days)).joinToString(" · ")
        return launch(intent, context.getString(R.string.note_alarm, time), "No alarm app on this watch.")
    }

    /**
     * The clock app's timers or alarms, for what no intent can do: read, pause or stop a running timer,
     * turn an alarm off or delete it.
     */
    private fun showClock(args: JsonObject): Outcome {
        if (!PermissionBroker.hostVisible) return Outcome(error("The watch screen is off, so the clock cannot open. Ask the user to look at the watch and ask again."))
        val alarms = args["page"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase() == "alarms"
        return launch(
            Intent(if (alarms) AlarmClock.ACTION_SHOW_ALARMS else AlarmClock.ACTION_SHOW_TIMERS),
            context.getString(if (alarms) R.string.note_show_alarms else R.string.note_show_timers),
            "The clock app on this watch cannot be opened on that page. If you can open apps, open its timer or alarm app by name.",
            endsConversation = true,
        )
    }

    /** "mon, tue" (or the whole names) as days of the week, in order and each once. */
    private fun weekdays(text: String?): List<DayOfWeek> =
        text.orEmpty().lowercase().split(',', ' ', ';').mapNotNull { word ->
            DayOfWeek.entries.firstOrNull { word.length >= 2 && it.name.lowercase().startsWith(word.take(3)) }
        }.distinct().sorted()

    private fun calendarDay(day: DayOfWeek): Int = day.value % 7 + 1 // java.util.Calendar: Sunday is 1, Monday 2

    /** The days for the transcript in the app's language: a run of three or more as "Mon – Fri". */
    private fun daysText(days: List<DayOfWeek>): String? {
        if (days.isEmpty()) return null
        val locale = context.resources.configuration.locales[0]
        fun short(day: DayOfWeek) = day.getDisplayName(TextStyle.SHORT, locale)
        val run = days.size >= 3 && days.zipWithNext().all { (a, b) -> b.value == a.value + 1 }
        return if (run) "${short(days.first())} – ${short(days.last())}" else days.joinToString(", ", transform = ::short)
    }

    private fun saveNote(args: JsonObject): Outcome {
        val text = args["text"]?.jsonPrimitive?.contentOrNull?.trim()?.take(2_000)
        if (text.isNullOrEmpty()) return Outcome(error("text is empty"))
        return Outcome(ok(), note = context.getString(R.string.note_saved), savedNote = text)
    }

    private suspend fun search(args: JsonObject): Outcome {
        val query = args["query"]?.jsonPrimitive?.contentOrNull?.trim()
        if (query.isNullOrEmpty()) return Outcome(error("query is empty"))
        val search = webSearch ?: return Outcome(error("Web search is turned off."))
        return try {
            val summary = withContext(Dispatchers.IO) { search(query) }
            Outcome(buildJsonObject { put("summary", summary) }, note = context.getString(R.string.note_searched))
        } catch (e: Exception) {
            Outcome(error("The search failed: ${e.message}"))
        }
    }

    private suspend fun remember(args: JsonObject): Outcome {
        val store = memory ?: return Outcome(error("The memory is turned off in Talova's settings under ${setting(R.string.memory_title)}."))
        val text = args["fact"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val replaces = args["replaces"]?.jsonPrimitive?.intOrNull
        return when (val change = store.remember(text, replaces)) {
            is Memory.Change.Saved -> Outcome(
                buildJsonObject {
                    put("ok", true)
                    put("number", change.fact.number)
                },
                note = context.getString(R.string.note_remembered, change.fact.text),
                notice = context.getString(R.string.notice_remembered),
            )
            is Memory.Change.Known -> Outcome(buildJsonObject {
                put("ok", true)
                put("already_known", change.fact.number)
            })
            is Memory.Change.Refused -> Outcome(error(change.reason))
            is Memory.Change.Forgotten -> Outcome(ok())
        }
    }

    private suspend fun forget(args: JsonObject): Outcome {
        val store = memory ?: return Outcome(error("The memory is turned off in Talova's settings under ${setting(R.string.memory_title)}."))
        val number = args["number"]?.jsonPrimitive?.intOrNull ?: return Outcome(error("number is missing"))
        return when (val change = store.forget(number)) {
            is Memory.Change.Forgotten -> Outcome(
                ok(),
                note = context.getString(R.string.note_forgotten, change.fact.text),
                notice = context.getString(R.string.notice_forgotten),
            )
            is Memory.Change.Refused -> Outcome(error(change.reason))
            else -> Outcome(ok())
        }
    }

    // Started from the service, so a new task is required; the clock app shows nothing (EXTRA_SKIP_UI).
    @android.annotation.SuppressLint("WearRecents")
    private fun launch(intent: Intent, note: String, unavailable: String, endsConversation: Boolean = false): Outcome {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) == null) return Outcome(error(unavailable))
        return try {
            context.startActivity(intent)
            Outcome(ok(), note = note, endsConversation = endsConversation)
        } catch (e: Exception) {
            Outcome(error("The watch did not allow it: ${e.message}"))
        }
    }

    /** A settings page by the name the user sees, so the model can point them to it in their language. */
    private fun setting(res: Int) = "\"${context.getString(res)}\""

    private fun ok() = buildJsonObject { put("ok", true) }

    private fun error(message: String) = buildJsonObject {
        put("ok", false)
        put("error", message)
    }

    companion object {
        private const val MEDIA_SETTLE_MS = 600L
        private const val MAX_APP_NAMES = 60

        fun formatDuration(seconds: Int): String = when {
            seconds < 60 -> "$seconds s"
            seconds % 3600 == 0 -> "${seconds / 3600} h"
            seconds >= 3600 -> "${seconds / 3600} h ${seconds % 3600 / 60} min"
            seconds % 60 == 0 -> "${seconds / 60} min"
            else -> "${seconds / 60} min ${seconds % 60} s"
        }

        fun searchWith(search: OpenAiWebSearch, apiKey: String): suspend (String) -> String = { query -> search.search(apiKey, query) }
    }
}
