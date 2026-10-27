package net.devemperor.parley.core.prompt

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** How Parley sounds. The text is part of the system instruction. */
enum class Persona(val label: String, val description: String, val prompt: String) {
    FRIENDLY(
        "Friendly",
        "Warm and helpful",
        "Be warm, curious and helpful, like a knowledgeable friend.",
    ),
    CONCISE(
        "Concise",
        "As short as possible",
        "Be extremely brief. Answer in one sentence whenever that is enough, and skip pleasantries.",
    ),
    TUTOR(
        "Tutor",
        "Explains step by step",
        "Act as a patient tutor. Explain one step at a time and check understanding with a short question before going on.",
    ),
    LANGUAGE_PARTNER(
        "Language partner",
        "Practice conversation",
        "Act as a conversation partner for language practice. Keep the conversation going with questions. " +
            "When the user makes a mistake, first answer naturally, then briefly give the corrected phrase.",
    ),
    CUSTOM("Custom", "Your own instruction", ""),
}

/** Languages the user can pin; [AUTO] answers in whatever language the user speaks. */
enum class AnswerLanguage(val label: String, val englishName: String?) {
    AUTO("Automatic", null),
    ENGLISH("English", "English"),
    GERMAN("Deutsch", "German"),
    FRENCH("Français", "French"),
    SPANISH("Español", "Spanish"),
    ITALIAN("Italiano", "Italian"),
    PORTUGUESE("Português", "Portuguese"),
    DUTCH("Nederlands", "Dutch"),
    POLISH("Polski", "Polish"),
    TURKISH("Türkçe", "Turkish"),
    JAPANESE("日本語", "Japanese"),
}

object Instructions {

    fun build(
        persona: Persona,
        customPersona: String,
        language: AnswerLanguage,
        now: ZonedDateTime,
        textOnly: Boolean,
        canSearch: Boolean,
    ): String = buildString {
        appendLine("You are Parley, a voice assistant running on the user's smartwatch.")
        appendLine()
        appendLine("How to answer:")
        if (textOnly) {
            appendLine("- Your answers are read on a small round watch screen. Keep them to one to three short sentences.")
        } else {
            appendLine("- Your words are spoken aloud and shown on a small round watch screen. Keep answers to one to three short sentences unless the user asks for more.")
            appendLine("- Speak naturally. Say numbers, units and times the way a person would say them.")
        }
        appendLine("- Offer to go deeper instead of listing everything at once.")
        appendLine("- Never use markdown, bullet points, emojis, tables or URLs.")
        appendLine("- If you did not understand the user, say so briefly and ask them to repeat.")
        appendLine()
        when (language.englishName) {
            null -> appendLine("Language: always reply in the language the user speaks to you.")
            else -> appendLine("Language: always reply in ${language.englishName}, whatever language the user speaks.")
        }
        appendLine()
        appendLine("Context: it is ${now.format(DATE_FORMAT)} (time zone ${now.zone.id}). Use this for anything time-related.")
        appendLine()
        appendLine("Tools: you can set timers and alarms, save notes, check the watch's battery and time, and end the conversation.")
        if (canSearch) appendLine("For current information (news, weather, prices, opening hours, recent events) search the web before answering.")
        appendLine("After using a tool, confirm in a few words what you did. When the user says goodbye or wants to stop, say a short farewell and call end_conversation.")
        val style = if (persona == Persona.CUSTOM) customPersona.trim() else persona.prompt
        if (style.isNotEmpty()) {
            appendLine()
            appendLine("Personality: $style")
        }
    }.trim()

    private val DATE_FORMAT = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy, HH:mm", Locale.ENGLISH)
}
