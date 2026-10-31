package net.devemperor.parley.core.prompt

import net.devemperor.parley.core.conversation.Role
import net.devemperor.parley.core.conversation.Turn
import net.devemperor.parley.core.memory.Fact
import net.devemperor.parley.core.tools.WatchFeature
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * How Parley sounds, and so how long it talks: there is no separate setting for the length. [length]
 * takes the place of the general rule in [Instructions] (beside it, Gemini kept to the general one);
 * a persona without one gets that rule. The texts are part of the system instruction.
 */
enum class Persona(val label: String, val description: String, val prompt: String, val length: String? = null) {
    FRIENDLY(
        "Friendly",
        "Warm and helpful",
        "Be warm, curious and helpful, like a knowledgeable friend.",
    ),
    CONCISE(
        "Concise",
        "As short as possible",
        "Be extremely brief and skip pleasantries.",
        length = "Answer in one sentence whenever that is enough.",
    ),
    DETAILED(
        "Detailed",
        "Explains with background and examples",
        "Be thorough, like a knowledgeable friend who enjoys explaining: give the background, how it works, an " +
            "example or two and what follows from it. Still talk rather than lecture, and stop once the point is made.",
        length = "Give a simple question a short answer, but talk about anything else for about a minute, roughly 150 words.",
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
        length = "Keep your turns to a sentence or two, so the user does most of the talking.",
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

/**
 * The interpreter's languages, each by its own name, so nobody needs a translation to find theirs.
 * [code] is the ISO 639-1 code, stored in the settings and shown in short ("DE ⇄ ES").
 */
enum class InterpretLanguage(val code: String, val ownName: String, val englishName: String) {
    GERMAN("de", "Deutsch", "German"),
    ENGLISH("en", "English", "English"),
    SPANISH("es", "Español", "Spanish"),
    FRENCH("fr", "Français", "French"),
    ITALIAN("it", "Italiano", "Italian"),
    PORTUGUESE("pt", "Português", "Portuguese"),
    DUTCH("nl", "Nederlands", "Dutch"),
    POLISH("pl", "Polski", "Polish"),
    SWEDISH("sv", "Svenska", "Swedish"),
    TURKISH("tr", "Türkçe", "Turkish"),
    GREEK("el", "Ελληνικά", "Greek"),
    RUSSIAN("ru", "Русский", "Russian"),
    UKRAINIAN("uk", "Українська", "Ukrainian"),
    ARABIC("ar", "العربية", "Arabic"),
    HINDI("hi", "हिन्दी", "Hindi"),
    INDONESIAN("id", "Bahasa Indonesia", "Indonesian"),
    VIETNAMESE("vi", "Tiếng Việt", "Vietnamese"),
    THAI("th", "ไทย", "Thai"),
    JAPANESE("ja", "日本語", "Japanese"),
    KOREAN("ko", "한국어", "Korean"),
    CHINESE("zh", "中文", "Mandarin Chinese");

    companion object {
        fun fromCode(code: String?): InterpretLanguage? = entries.firstOrNull { it.code == code }
    }
}

object Instructions {

    /**
     * The interpreter: what is said in one language comes out in the other, and nothing else. No
     * persona, memory or tools: an interpreter that answers a question instead of passing it on is
     * worse than none.
     */
    fun interpreter(first: InterpretLanguage, second: InterpretLanguage): String = buildString {
        val a = first.englishName
        val b = second.englishName
        appendLine("You are an interpreter between $a and $b. Two people talk through a smartwatch held between them, and your words are spoken aloud.")
        appendLine()
        appendLine("- When you hear $a, say the same in $b. When you hear $b, say the same in $a.")
        appendLine("- Translate faithfully and completely, in the first person, as if you were the speaker: keep the meaning, the tone, names and numbers.")
        appendLine("- Never answer a question yourself, never comment, explain, greet or add anything, and never say that you are translating. A question is translated, not answered.")
        appendLine("- If you could not make out what was said, ask in the speaker's language, in a few words, to repeat it.")
        appendLine("- Say numbers, units and times the way a person would say them.")
        appendLine("- Never use markdown, emojis or URLs.")
    }.trim()

    fun build(
        persona: Persona,
        customPersona: String,
        language: AnswerLanguage,
        now: ZonedDateTime,
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
        appendLine("- Your words are spoken aloud and shown on a small round watch screen. ${persona.length ?: DEFAULT_LENGTH} The user can interrupt you at any time.")
        appendLine("- Speak naturally. Say numbers, units and times the way a person would say them.")
        appendLine("- Never read out a long list: give the most useful part and offer more.")
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
            "Tools: you can set timers and alarms, open the watch's timers and alarms, save notes, check the watch's battery and time, " +
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
            if (WatchFeature.PHONE in features) {
                appendLine("Before preparing a message, say its text and whom it is for, and prepare it only after the user's yes. It then waits on the phone for the user to send: say so, never that it was sent.")
                appendLine(
                    if (WatchFeature.CALLS in features) "For a message to a contact, find the number with find_contact first."
                    else "For a message to a contact, leave the number out: the user picks the contact on the phone.",
                )
            }
        }
        appendLine("For a reminder at a time, set an alarm with what to remember as its label.")
        appendLine("You cannot see, pause or stop a running timer, nor turn an alarm off, change or delete it: for that, open the timers or alarms with show_clock.")
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

    // A length in sentences: told only "a few sentences", Gemini kept to two (measured 30.10.).
    private const val DEFAULT_LENGTH = "Unless your personality below says otherwise, give a simple question a short answer, " +
        "and a question about how or why something is a real explanation of four to six sentences."

    private val DATE_FORMAT = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy, HH:mm", Locale.ENGLISH)
}
