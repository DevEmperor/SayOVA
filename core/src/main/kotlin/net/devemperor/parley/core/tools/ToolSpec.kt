package net.devemperor.parley.core.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * A function the model may call. Parameters are declared once and rendered in each provider's schema
 * dialect: Gemini takes the OpenAPI subset with upper-case type names, OpenAI takes JSON Schema.
 */
data class ToolSpec(
    val name: String,
    val description: String,
    val parameters: List<ToolParam> = emptyList(),
) {
    fun openAiSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            parameters.forEach { p ->
                putJsonObject(p.name) {
                    put("type", p.type.jsonSchema)
                    put("description", p.description)
                }
            }
        }
        putJsonArray("required") { parameters.filter { it.required }.forEach { add(it.name) } }
        put("additionalProperties", false)
    }

    fun geminiSchema(): JsonObject = buildJsonObject {
        put("type", "OBJECT")
        putJsonObject("properties") {
            parameters.forEach { p ->
                putJsonObject(p.name) {
                    put("type", p.type.openApi)
                    put("description", p.description)
                }
            }
        }
        putJsonArray("required") { parameters.filter { it.required }.forEach { add(it.name) } }
    }
}

data class ToolParam(
    val name: String,
    val type: ParamType,
    val description: String,
    val required: Boolean = true,
)

enum class ParamType(val jsonSchema: String, val openApi: String) {
    STRING("string", "STRING"),
    INTEGER("integer", "INTEGER"),
    NUMBER("number", "NUMBER"),
    BOOLEAN("boolean", "BOOLEAN"),
}

/** The tools Parley offers the model. The watch side implements them (see the app's ToolRunner). */
object ParleyTools {
    const val DEVICE_STATUS = "get_device_status"
    const val SET_TIMER = "set_timer"
    const val SET_ALARM = "set_alarm"
    const val SAVE_NOTE = "save_note"
    const val END_CONVERSATION = "end_conversation"
    const val SEARCH_WEB = "search_web"
    const val REMEMBER_FACT = "remember_fact"
    const val FORGET_FACT = "forget_fact"

    val deviceStatus = ToolSpec(
        name = DEVICE_STATUS,
        description = "Returns the watch's current local date, time, time zone and battery level.",
    )

    val setTimer = ToolSpec(
        name = SET_TIMER,
        description = "Starts a countdown timer on the watch.",
        parameters = listOf(
            ToolParam("seconds", ParamType.INTEGER, "Length of the timer in seconds, 1 to 86400."),
            ToolParam("label", ParamType.STRING, "Short name for the timer, e.g. 'Tea'.", required = false),
        ),
    )

    val setAlarm = ToolSpec(
        name = SET_ALARM,
        description = "Sets an alarm on the watch for the next occurrence of the given local time.",
        parameters = listOf(
            ToolParam("hour", ParamType.INTEGER, "Hour in 24-hour format, 0 to 23."),
            ToolParam("minute", ParamType.INTEGER, "Minute, 0 to 59."),
            ToolParam("label", ParamType.STRING, "Short name for the alarm.", required = false),
        ),
    )

    val saveNote = ToolSpec(
        name = SAVE_NOTE,
        description = "Saves a short note for the user. It appears in Parley's history on the watch.",
        parameters = listOf(ToolParam("text", ParamType.STRING, "The note, written as the user would want to read it later.")),
    )

    val endConversation = ToolSpec(
        name = END_CONVERSATION,
        description = "Ends the conversation. Call it when the user says goodbye or asks you to stop, after a brief farewell.",
    )

    /** Only for providers without built-in web search; the app answers it with a separate request. */
    val searchWeb = ToolSpec(
        name = SEARCH_WEB,
        description = "Searches the web for current information (news, weather, opening hours, prices, recent events). Returns a short summary.",
        parameters = listOf(ToolParam("query", ParamType.STRING, "What to search for, as a short search query.")),
    )

    /** Only while the memory is on; the facts themselves are in the instructions (see Instructions). */
    val rememberFact = ToolSpec(
        name = REMEMBER_FACT,
        description = "Saves a lasting fact about the user to Parley's memory, which every future conversation is given. " +
            "Call it only when the user explicitly asks you to remember something about them.",
        parameters = listOf(
            // No examples here: English ones made the model save German facts in English.
            ToolParam("fact", ParamType.STRING, "The fact as a short statement about the user, in the language the user speaks to you, never translated. The user reads it in a list on the watch."),
            ToolParam("replaces", ParamType.INTEGER, "Number of a remembered fact this one updates, e.g. a new home town.", required = false),
        ),
    )

    val forgetFact = ToolSpec(
        name = FORGET_FACT,
        description = "Removes a fact from Parley's memory when the user asks you to forget it.",
        parameters = listOf(ToolParam("number", ParamType.INTEGER, "Number of the remembered fact.")),
    )

    val standard = listOf(deviceStatus, setTimer, setAlarm, saveNote, endConversation)
    val memory = listOf(rememberFact, forgetFact)
}
