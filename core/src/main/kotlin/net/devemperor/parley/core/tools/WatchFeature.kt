package net.devemperor.parley.core.tools

/**
 * The watch's own sensors, data and abilities the model may use, most behind an Android permission
 * the user grants once. [capability] completes the instructions' list of what Parley can do.
 */
enum class WatchFeature(val tools: List<ToolSpec>, val capability: String) {
    LOCATION(listOf(ParleyTools.getLocation), "find out where the watch is"),
    WEATHER(listOf(ParleyTools.getWeather), "tell the weather"),
    HEART_RATE(listOf(ParleyTools.measureHeartRate), "measure the user's heart rate"),
    STEPS(listOf(ParleyTools.getStepsToday), "read today's step count"),
    PRESSURE(listOf(ParleyTools.getAirPressure), "read the air pressure and altitude"),
    CALENDAR(listOf(ParleyTools.getCalendarEvents), "read the user's calendar"),
    ALARM(listOf(ParleyTools.getNextAlarm), "tell when the next alarm rings"),
    MUSIC(listOf(ParleyTools.controlMedia), "control music playback"),
    CALLS(listOf(ParleyTools.findContact, ParleyTools.callPhone), "look up contacts and make phone calls"),
    APPS(listOf(ParleyTools.openApp), "open apps"),
    NOTIFICATIONS(listOf(ParleyTools.getNotifications), "read the watch's notifications"),
    PHONE(
        listOf(ParleyTools.prepareMessage, ParleyTools.prepareEvent, ParleyTools.prepareRoute),
        "prepare messages, e-mails, calendar events and routes on the user's phone for them to send or start there",
    ),
    ;

    companion object {
        fun forTool(name: String): WatchFeature? = entries.firstOrNull { feature -> feature.tools.any { it.name == name } }

        fun fromName(name: String?): WatchFeature? = entries.firstOrNull { it.name == name }
    }
}
