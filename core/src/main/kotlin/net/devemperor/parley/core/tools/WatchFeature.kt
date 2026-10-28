package net.devemperor.parley.core.tools

/**
 * The watch's own sensors and data the model may use, each behind an Android permission the user
 * grants once. [capability] completes the instructions' list of what Parley can do.
 */
enum class WatchFeature(val tool: ToolSpec, val capability: String) {
    LOCATION(ParleyTools.getLocation, "find out where the watch is"),
    HEART_RATE(ParleyTools.measureHeartRate, "measure the user's heart rate"),
    STEPS(ParleyTools.getStepsToday, "read today's step count"),
    CALENDAR(ParleyTools.getCalendarEvents, "read the user's calendar"),
    ;

    companion object {
        fun forTool(name: String): WatchFeature? = entries.firstOrNull { it.tool.name == name }

        fun fromName(name: String?): WatchFeature? = entries.firstOrNull { it.name == name }
    }
}
