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
import net.devemperor.parley.core.provider.openai.OpenAiWebSearch
import net.devemperor.parley.core.session.VoiceEvent
import net.devemperor.parley.core.tools.ParleyTools
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Carries out the model's tool calls on the watch. Every call answers, also when it fails, so the
 * model can tell the user what happened instead of waiting.
 */
class ToolRunner(
    private val context: Context,
    private val webSearch: (suspend (String) -> String)?,
) {
    /** [note] is shown in the transcript as what Parley did, e.g. "Timer · 5 min". */
    data class Outcome(val result: JsonObject, val note: String? = null, val savedNote: String? = null, val endsConversation: Boolean = false)

    suspend fun run(call: VoiceEvent.ToolCall): Outcome = when (call.name) {
        ParleyTools.DEVICE_STATUS -> deviceStatus()
        ParleyTools.SET_TIMER -> setTimer(call.arguments)
        ParleyTools.SET_ALARM -> setAlarm(call.arguments)
        ParleyTools.SAVE_NOTE -> saveNote(call.arguments)
        ParleyTools.END_CONVERSATION -> Outcome(ok(), endsConversation = true)
        ParleyTools.SEARCH_WEB -> search(call.arguments)
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
        return launch(intent, "Timer · ${formatDuration(seconds)}", "No timer app on this watch.")
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
        return launch(intent, "Alarm · %02d:%02d".format(hour, minute), "No alarm app on this watch.")
    }

    private fun saveNote(args: JsonObject): Outcome {
        val text = args["text"]?.jsonPrimitive?.contentOrNull?.trim()?.take(2_000)
        if (text.isNullOrEmpty()) return Outcome(error("text is empty"))
        return Outcome(ok(), note = "Note saved", savedNote = text)
    }

    private suspend fun search(args: JsonObject): Outcome {
        val query = args["query"]?.jsonPrimitive?.contentOrNull?.trim()
        if (query.isNullOrEmpty()) return Outcome(error("query is empty"))
        val search = webSearch ?: return Outcome(error("Web search is turned off."))
        return try {
            val summary = withContext(Dispatchers.IO) { search(query) }
            Outcome(buildJsonObject { put("summary", summary) }, note = "Searched the web")
        } catch (e: Exception) {
            Outcome(error("The search failed: ${e.message}"))
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
