package net.devemperor.parley.session

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.provider.AlarmClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.devemperor.parley.R
import net.devemperor.parley.core.memory.Memory
import net.devemperor.parley.core.provider.openai.OpenAiWebSearch
import net.devemperor.parley.core.session.VoiceEvent
import net.devemperor.parley.core.tools.ParleyTools
import net.devemperor.parley.core.tools.WatchFeature
import net.devemperor.parley.data.MemoryStore
import net.devemperor.parley.features.PermissionBroker
import net.devemperor.parley.features.WatchFeatures
import net.devemperor.parley.features.WatchSensors
import net.devemperor.parley.parley
import net.devemperor.parley.ui.labelRes
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

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
) {
    private val sensors = WatchSensors(context)

    /**
     * [note] is shown in the transcript as what Parley did, e.g. "Timer · 5 min"; [notice] is the
     * shorter text under the orb, the note itself when not given.
     */
    data class Outcome(
        val result: JsonObject,
        val note: String? = null,
        val notice: String? = null,
        val savedNote: String? = null,
        val endsConversation: Boolean = false,
    )

    /** [onGranted] runs when the user allows a watch feature during the call, before it is used. */
    suspend fun run(call: VoiceEvent.ToolCall, onGranted: () -> Unit = {}): Outcome = when (call.name) {
        ParleyTools.DEVICE_STATUS -> deviceStatus()
        ParleyTools.SET_TIMER -> setTimer(call.arguments)
        ParleyTools.SET_ALARM -> setAlarm(call.arguments)
        ParleyTools.SAVE_NOTE -> saveNote(call.arguments)
        ParleyTools.END_CONVERSATION -> Outcome(ok(), endsConversation = true)
        ParleyTools.SEARCH_WEB -> search(call.arguments)
        ParleyTools.REMEMBER_FACT -> remember(call.arguments)
        ParleyTools.FORGET_FACT -> forget(call.arguments)
        else -> WatchFeature.forTool(call.name)?.let { watchFeature(it, call.arguments, onGranted) } ?: Outcome(error("Unknown tool ${call.name}"))
    }

    /** A watch feature: asks for its permission the first time, then reads the sensor or data. */
    private suspend fun watchFeature(feature: WatchFeature, args: JsonObject, onGranted: () -> Unit): Outcome {
        val name = context.getString(feature.labelRes())
        val where = "Parley's settings under ${setting(R.string.features_title)}"
        if (feature !in features) return Outcome(error("The user turned this off in $where."))
        if (!WatchFeatures.granted(context, feature)) {
            context.parley.settings.update { it.copy(featuresAsked = it.featuresAsked + feature) }
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
                    error("Parley needs the user's permission first, but the watch screen is off. Ask the user to look at the watch and ask again, or to allow it in $where."),
                )
            }
        }
        return try {
            when (feature) {
                WatchFeature.LOCATION -> location()
                WatchFeature.HEART_RATE -> heartRate()
                WatchFeature.STEPS -> steps()
                WatchFeature.CALENDAR -> calendar(args)
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
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .apply { label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) } }
        return launch(intent, context.getString(R.string.note_alarm, "%02d:%02d".format(hour, minute)), "No alarm app on this watch.")
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
        val store = memory ?: return Outcome(error("The memory is turned off in Parley's settings under ${setting(R.string.memory_title)}."))
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
        val store = memory ?: return Outcome(error("The memory is turned off in Parley's settings under ${setting(R.string.memory_title)}."))
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
    private fun launch(intent: Intent, note: String, unavailable: String): Outcome {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) == null) return Outcome(error(unavailable))
        return try {
            context.startActivity(intent)
            Outcome(ok(), note = note)
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
