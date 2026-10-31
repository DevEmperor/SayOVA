package net.devemperor.talova.core.tools

/**
 * The watch's own sensors, data and abilities the model may use, most behind an Android permission
 * the user grants once. [capability] completes the instructions' list of what Talova can do.
 */
enum class WatchFeature(val tools: List<ToolSpec>, val capability: String) {
    LOCATION(listOf(TalovaTools.getLocation), "find out where the watch is"),
    WEATHER(listOf(TalovaTools.getWeather), "tell the weather"),
    HEART_RATE(listOf(TalovaTools.measureHeartRate), "measure the user's heart rate"),
    STEPS(listOf(TalovaTools.getStepsToday), "read today's step count"),
    PRESSURE(listOf(TalovaTools.getAirPressure), "read the air pressure and altitude"),
    CALENDAR(listOf(TalovaTools.getCalendarEvents), "read the user's calendar"),
    ALARM(listOf(TalovaTools.getNextAlarm), "tell when the next alarm rings"),
    MUSIC(listOf(TalovaTools.controlMedia), "control music playback"),
    CALLS(listOf(TalovaTools.findContact, TalovaTools.callPhone), "look up contacts and make phone calls"),
    APPS(listOf(TalovaTools.openApp), "open apps"),
    NOTIFICATIONS(listOf(TalovaTools.getNotifications), "read the watch's notifications"),
    PHONE(
        listOf(TalovaTools.prepareMessage, TalovaTools.prepareEvent, TalovaTools.prepareRoute),
        "prepare messages, e-mails, calendar events and places to go on the user's phone for them to send, add or start there",
    ),
    ;

    companion object {
        fun forTool(name: String): WatchFeature? = entries.firstOrNull { feature -> feature.tools.any { it.name == name } }

        fun fromName(name: String?): WatchFeature? = entries.firstOrNull { it.name == name }
    }
}
