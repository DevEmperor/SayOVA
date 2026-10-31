package net.devemperor.talova.core.tools

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

/** The tools Talova offers the model. The watch side implements them (see the app's ToolRunner). */
object TalovaTools {
    const val DEVICE_STATUS = "get_device_status"
    const val SET_TIMER = "set_timer"
    const val SET_ALARM = "set_alarm"
    const val SHOW_CLOCK = "show_clock"
    const val SAVE_NOTE = "save_note"
    const val END_CONVERSATION = "end_conversation"
    const val SEARCH_WEB = "search_web"
    const val REMEMBER_FACT = "remember_fact"
    const val FORGET_FACT = "forget_fact"
    const val GET_LOCATION = "get_location"
    const val MEASURE_HEART_RATE = "measure_heart_rate"
    const val GET_STEPS_TODAY = "get_steps_today"
    const val GET_CALENDAR_EVENTS = "get_calendar_events"
    const val CONTROL_MEDIA = "control_media"
    const val GET_NEXT_ALARM = "get_next_alarm"
    const val FIND_CONTACT = "find_contact"
    const val CALL_PHONE = "call_phone"
    const val OPEN_APP = "open_app"
    const val GET_WEATHER = "get_weather"
    const val GET_AIR_PRESSURE = "get_air_pressure"
    const val GET_NOTIFICATIONS = "get_notifications"
    const val PREPARE_MESSAGE = "prepare_message"
    const val PREPARE_EVENT = "prepare_calendar_event"
    const val PREPARE_ROUTE = "prepare_route"

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
            ToolParam(
                "days", ParamType.STRING,
                "For an alarm that repeats every week: the days it rings on, comma-separated from mon, tue, wed, thu, fri, sat, sun. Leave out for a single alarm.",
                required = false,
            ),
        ),
    )

    // No tool turns an alarm off: the clock's API for it only dismisses one that is ringing (Google's
    // Wear OS clock took the request for an alarm the next morning and left it on).
    val showClock = ToolSpec(
        name = SHOW_CLOCK,
        description = "Opens the watch's clock app on its timers or its alarms, for what you cannot do yourself: see how long a timer has left, " +
            "pause or stop it, or turn an alarm off, change or delete it. The conversation ends for it.",
        parameters = listOf(ToolParam("page", ParamType.STRING, "One of: timers, alarms.")),
    )

    val saveNote = ToolSpec(
        name = SAVE_NOTE,
        description = "Saves a short note for the user. It appears in Talova's history on the watch.",
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
        description = "Saves a lasting fact about the user to Talova's memory, which every future conversation is given. " +
            "Call it only when the user explicitly asks you to remember something about them.",
        parameters = listOf(
            // No examples here: English ones made the model save German facts in English.
            ToolParam("fact", ParamType.STRING, "The fact as a short statement about the user, in the language the user speaks to you, never translated. The user reads it in a list on the watch."),
            ToolParam("replaces", ParamType.INTEGER, "Number of a remembered fact this one updates, e.g. a new home town.", required = false),
        ),
    )

    val forgetFact = ToolSpec(
        name = FORGET_FACT,
        description = "Removes a fact from Talova's memory when the user asks you to forget it.",
        parameters = listOf(ToolParam("number", ParamType.INTEGER, "Number of the remembered fact.")),
    )

    // The watch's own sensors and data, each behind a permission (see WatchFeature).
    val getLocation = ToolSpec(
        name = GET_LOCATION,
        description = "Returns where the watch is now: latitude, longitude, accuracy in metres and, when known, the place name. " +
            "Use it for places nearby or the way somewhere, then search the web if needed; for the weather use get_weather.",
    )

    val measureHeartRate = ToolSpec(
        name = MEASURE_HEART_RATE,
        description = "Measures the user's heart rate now with the watch's sensor and returns beats per minute. " +
            "It takes up to half a minute: first tell the user to keep the watch on and hold still.",
    )

    val getStepsToday = ToolSpec(
        name = GET_STEPS_TODAY,
        description = "Returns how many steps the watch has counted today.",
    )

    val getCalendarEvents = ToolSpec(
        name = GET_CALENDAR_EVENTS,
        description = "Returns the user's calendar events that have not ended yet: title, start, end, all-day and location.",
        parameters = listOf(
            ToolParam("days", ParamType.INTEGER, "How many days to cover, starting today: 1 is the rest of today, 2 adds tomorrow; at most 14.", required = false),
        ),
    )

    val controlMedia = ToolSpec(
        name = CONTROL_MEDIA,
        description = "Controls what is playing on the watch (music, podcasts): play, pause, next or previous. " +
            "Returns what is playing when the watch tells. After play, say a word and end the conversation unless the user wants more.",
        parameters = listOf(ToolParam("action", ParamType.STRING, "One of: play, pause, next, previous.")),
    )

    val getNextAlarm = ToolSpec(
        name = GET_NEXT_ALARM,
        description = "Returns when the next alarm on the watch rings, or that none is set.",
    )

    val findContact = ToolSpec(
        name = FIND_CONTACT,
        description = "Looks up the user's contacts on the watch by name and returns the matching names with their phone numbers.",
        parameters = listOf(ToolParam("name", ParamType.STRING, "The name or part of it, as the user said it.")),
    )

    val callPhone = ToolSpec(
        name = CALL_PHONE,
        description = "Calls a phone number from the watch once this conversation has ended, which it then does by itself. " +
            "Only after the user confirmed whom to call: look the number up with find_contact and ask first.",
        parameters = listOf(
            ToolParam("number", ParamType.STRING, "The phone number to call, as find_contact returned it."),
            ToolParam("name", ParamType.STRING, "Who is called, for the screen.", required = false),
        ),
    )

    val openApp = ToolSpec(
        name = OPEN_APP,
        description = "Opens an app on the watch by its name; the conversation ends for it. " +
            "When no app matches, returns the names of the installed apps to choose from.",
        parameters = listOf(ToolParam("name", ParamType.STRING, "The app's name as the user said it, e.g. 'Samsung Health' or 'stopwatch'.")),
    )

    val getWeather = ToolSpec(
        name = GET_WEATHER,
        description = "Returns the weather now and the forecast for today and tomorrow from the Norwegian Meteorological Institute: " +
            "for where the watch is, or for another place when you pass its coordinates. Use it instead of a web search for the weather.",
        parameters = listOf(
            ToolParam("latitude", ParamType.NUMBER, "Latitude of another place; leave out for where the watch is.", required = false),
            ToolParam("longitude", ParamType.NUMBER, "Longitude of another place; leave out for where the watch is.", required = false),
            ToolParam("place", ParamType.STRING, "Name of that other place, for the screen.", required = false),
        ),
    )

    val getAirPressure = ToolSpec(
        name = GET_AIR_PRESSURE,
        description = "Reads the watch's barometer: the air pressure in hPa and the altitude in metres it suggests " +
            "(more exact when the local sea-level pressure is known).",
    )

    val getNotifications = ToolSpec(
        name = GET_NOTIFICATIONS,
        description = "Returns the notifications on the watch now, newest first: app, title, text and time. " +
            "Read them out briefly, not word for word unless asked.",
    )

    // Prepared on the paired phone, where the user sends or starts it: the watch never sends anything itself.
    val prepareMessage = ToolSpec(
        name = PREPARE_MESSAGE,
        description = "Prepares a message on the user's phone, ready for them to send there: WhatsApp, SMS, e-mail, " +
            "or any other messenger through the phone's share menu. Only after the user agreed to the text and whom it is for.",
        parameters = listOf(
            ToolParam("app", ParamType.STRING, "One of: whatsapp, sms, email, other (any other messenger: the user picks it on the phone)."),
            ToolParam("text", ParamType.STRING, "The message, written as the user wants to send it, in the language they wrote or spoke it in."),
            ToolParam(
                "to", ParamType.STRING,
                "For whatsapp or sms the phone number, with the country code where you know it (e.g. +49 171 2345678); for email the address. " +
                    "Leave out to let the user choose on the phone.",
                required = false,
            ),
            ToolParam("name", ParamType.STRING, "Whom it is for, for the screen.", required = false),
            ToolParam("subject", ParamType.STRING, "Subject of an e-mail.", required = false),
        ),
    )

    val prepareEvent = ToolSpec(
        name = PREPARE_EVENT,
        description = "Prepares a calendar event on the user's phone, filled in, for them to add to their calendar app there.",
        parameters = listOf(
            ToolParam("title", ParamType.STRING, "What the event is, as the user would write it."),
            ToolParam("start", ParamType.STRING, "Local start as date and time, e.g. 2026-11-02T15:00, or only a date, e.g. 2026-11-02, for an all-day event."),
            ToolParam("end", ParamType.STRING, "Local end in the same form; leave out for an hour (or the one day).", required = false),
            ToolParam("place", ParamType.STRING, "Where it is.", required = false),
            ToolParam("details", ParamType.STRING, "A note to go with it.", required = false),
        ),
    )

    val prepareRoute = ToolSpec(
        name = PREPARE_ROUTE,
        description = "Opens a place in the maps app on the user's phone, from where they start the route there with a tap.",
        parameters = listOf(
            ToolParam("destination", ParamType.STRING, "The place or address, as a maps app would find it."),
        ),
    )

    val standard = listOf(deviceStatus, setTimer, setAlarm, showClock, saveNote, endConversation)
    val memory = listOf(rememberFact, forgetFact)
}
