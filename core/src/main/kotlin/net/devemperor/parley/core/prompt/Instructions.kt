package net.devemperor.parley.core.prompt

import net.devemperor.parley.core.conversation.Role
import net.devemperor.parley.core.conversation.Turn
import net.devemperor.parley.core.memory.Fact
import net.devemperor.parley.core.tools.WatchFeature
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
        /** A conversation from the history this one carries on; empty for a new one. */
        earlier: List<Turn> = emptyList(),
        /** What Parley remembers about the user; null while the memory is turned off. */
        memory: List<Fact>? = null,
        /** The watch's own sensors and data offered as tools. */
        features: Set<WatchFeature> = emptySet(),
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
        // The thorough model says a few words while it thinks; it said them in English to German users.
        appendLine("This includes every word you say, also a short remark before you answer.")
        appendLine()
        appendLine("Context: it is ${now.format(DATE_FORMAT)} (time zone ${now.zone.id}). Use this for anything time-related.")
        appendLine()
        appendLine(
            "Tools: you can set timers and alarms, save notes, check the watch's battery and time, " +
                (if (memory != null) "remember facts about the user, " else "") +
                WatchFeature.entries.filter { it in features }.joinToString("") { "${it.capability}, " } +
                "and end the conversation.",
        )
        if (features.isNotEmpty()) {
            appendLine("Use the watch's sensors and data only when the user's request needs them. The first time, the watch asks the user to allow it; if a tool reports it is not allowed, tell the user briefly how to allow it.")
            if (WatchFeature.LOCATION in features || WatchFeature.WEATHER in features) appendLine("Name places, never read out coordinates.")
            if (WatchFeature.WEATHER in features) {
                appendLine("For the weather, use get_weather rather than a web search; say it in a sentence or two. For the weather here, call it without coordinates: it finds the watch's location itself.")
            }
            if (WatchFeature.HEART_RATE in features) appendLine("A heart rate from a watch is no medical measurement: never diagnose; if a value worries the user, suggest seeing a doctor.")
            if (WatchFeature.CALLS in features) {
                appendLine("Never call anyone without the user's clear yes to the name and number you found. Read phone numbers out digit by digit.")
            }
            if (WatchFeature.NOTIFICATIONS in features) appendLine("Read notifications only when the user asks for them.")
        }
        appendLine("For a reminder at a time, set an alarm with what to remember as its label.")
        if (canSearch) {
            val weather = if (WatchFeature.WEATHER in features) "" else "weather, "
            appendLine("For current information (news, ${weather}prices, opening hours, recent events) search the web before answering.")
        }
        appendLine("After using a tool, confirm in a few words what you did. When the user says goodbye or wants to stop, say a short farewell and call end_conversation.")
        val style = if (persona == Persona.CUSTOM) customPersona.trim() else persona.prompt
        if (style.isNotEmpty()) {
            appendLine()
            appendLine("Personality: $style")
        }
        appendLine()
        append(memorySection(memory))
        val transcript = transcriptOf(earlier)
        if (transcript.isNotEmpty()) {
            appendLine()
            appendLine("This conversation carries on an earlier one with the user. What was said so far, oldest first:")
            appendLine(transcript)
            appendLine("Continue from there: the user may refer to any of it. Do not repeat or summarise it unless asked.")
        }
    }.trim()

    private fun memorySection(memory: List<Fact>?): String = buildString {
        if (memory == null) {
            appendLine("Memory is off: nothing is kept between conversations. If the user asks you to remember something about them, say they can turn on the memory in Parley's settings, and offer to save a note instead.")
            return@buildString
        }
        appendLine("Memory: Parley keeps lasting facts about the user, given to every conversation.")
        appendLine("- Call remember_fact only when the user explicitly asks you to remember something lasting about them (name, home, family, preferences, diet, routines). Never save what they merely mention.")
        appendLine("- Write a fact as a short statement in the language the user speaks, not in English unless they speak English.")
        appendLine("- Things to read later or soon outdated (where they parked, a shopping list) are notes: use save_note.")
        appendLine("- Never remember passwords, PINs, card or account numbers.")
        appendLine("- When a fact updates one below, pass its number as replaces instead of saving it twice. When the user asks you to forget something, call forget_fact.")
        appendLine("- Use the facts where they help, without reciting them. Tell the user what you remember only when asked.")
        if (memory.isEmpty()) {
            appendLine("You remember nothing about the user yet.")
        } else {
            appendLine("What you remember (number, fact):")
            memory.forEach { appendLine("[${it.number}] ${it.text}") }
        }
    }

    /**
     * The earlier conversation as plain lines, the newest [EARLIER_LIMIT] characters of it: the
     * provider is paid by the token for every word, and what was said last matters most.
     */
    internal fun transcriptOf(turns: List<Turn>): String {
        val lines = turns.mapNotNull { turn ->
            val text = turn.text.trim().replace(Regex("\\s+"), " ")
            if (text.isEmpty()) return@mapNotNull null
            when (turn.role) {
                Role.USER -> "User: $text"
                Role.ASSISTANT -> "Parley: $text"
                Role.NOTE -> "(Note saved: $text)"
                Role.TOOL -> "(Done: $text)"
            }
        }
        val kept = ArrayDeque<String>()
        var length = 0
        for (line in lines.asReversed()) {
            if (length + line.length + 1 > EARLIER_LIMIT) break
            kept.addFirst(line)
            length += line.length + 1
        }
        return kept.joinToString("\n")
    }

    private const val EARLIER_LIMIT = 6_000

    private val DATE_FORMAT = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy, HH:mm", Locale.ENGLISH)
}
