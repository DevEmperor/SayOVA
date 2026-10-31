package net.devemperor.sayova.core.tools

/**
 * The watch's own sensors, data and abilities the model may use, most behind an Android permission
 * the user grants once. [capability] completes the instructions' list of what Sayova can do.
 */
enum class WatchFeature(val tools: List<ToolSpec>, val capability: String) {
    LOCATION(listOf(SayovaTools.getLocation), "find out where the watch is"),
    WEATHER(listOf(SayovaTools.getWeather), "tell the weather"),
    HEART_RATE(listOf(SayovaTools.measureHeartRate), "measure the user's heart rate"),
    STEPS(listOf(SayovaTools.getStepsToday), "read today's step count"),
    PRESSURE(listOf(SayovaTools.getAirPressure), "read the air pressure and altitude"),
    CALENDAR(listOf(SayovaTools.getCalendarEvents), "read the user's calendar"),
    ALARM(listOf(SayovaTools.getNextAlarm), "tell when the next alarm rings"),
    MUSIC(listOf(SayovaTools.controlMedia), "control music playback"),
    CALLS(listOf(SayovaTools.findContact, SayovaTools.callPhone), "look up contacts and make phone calls"),
    APPS(listOf(SayovaTools.openApp), "open apps"),
    NOTIFICATIONS(listOf(SayovaTools.getNotifications), "read the watch's notifications"),
    PHONE(
        listOf(SayovaTools.prepareMessage, SayovaTools.prepareEvent, SayovaTools.prepareRoute),
        "prepare messages, e-mails, calendar events and places to go on the user's phone for them to send, add or start there",
    ),
    ;

    companion object {
        fun forTool(name: String): WatchFeature? = entries.firstOrNull { feature -> feature.tools.any { it.name == name } }

        fun fromName(name: String?): WatchFeature? = entries.firstOrNull { it.name == name }
    }
}
