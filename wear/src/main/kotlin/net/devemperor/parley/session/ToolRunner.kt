package net.devemperor.parley.session

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.provider.AlarmClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
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
import net.devemperor.parley.data.MemoryStore
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Carries out the model's tool calls on the watch. Every call answers, also when it fails, so the
 * model can tell the user what happened instead of waiting.
 */
class ToolRunner(
    private val context: Context,
    private val webSearch: (suspend (String) -> String)?,
    /** Null while the memory is turned off; the model is then offered no memory tools either. */
    private val memory: MemoryStore?,
) {
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

    suspend fun run(call: VoiceEvent.ToolCall): Outcome = when (call.name) {
        ParleyTools.DEVICE_STATUS -> deviceStatus()
        ParleyTools.SET_TIMER -> setTimer(call.arguments)
        ParleyTools.SET_ALARM -> setAlarm(call.arguments)
        ParleyTools.SAVE_NOTE -> saveNote(call.arguments)
        ParleyTools.END_CONVERSATION -> Outcome(ok(), endsConversation = true)
        ParleyTools.SEARCH_WEB -> search(call.arguments)
        ParleyTools.REMEMBER_FACT -> remember(call.arguments)
        ParleyTools.FORGET_FACT -> forget(call.arguments)
        else -> Outcome(error("Unknown tool ${call.name}"))
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
        val store = memory ?: return Outcome(error("The memory is turned off in Parley's settings."))
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
        val store = memory ?: return Outcome(error("The memory is turned off in Parley's settings."))
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
