package net.devemperor.sayova.core.phone

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Links that open something prepared on the paired phone for the user to send or start there: a
 * message, an e-mail, a calendar event or a place to go. A watch can only open a browsable link on
 * the phone, so each is one the phone's own default apps take: wa.me for WhatsApp, sms: and mailto:
 * for the messages and mail apps, geo: for the maps app. No calendar app takes such a link for a new
 * event, so an event goes to the share page as [event], which hands the calendar app a calendar file.
 */
object PhoneLinks {

    /** WhatsApp's chat with [number] (digits with the country code, see [internationalDigits]), or its contact list without. */
    fun whatsApp(number: String?, text: String): String = "https://wa.me/${number.orEmpty()}?text=${encode(text)}"

    fun sms(number: String?, text: String): String = "sms:${number?.let(::dialable).orEmpty()}?body=${encode(text)}"

    fun email(address: String?, subject: String?, text: String): String {
        val query = listOfNotNull(subject?.takeIf { it.isNotBlank() }?.let { "subject=${encode(it)}" }, "body=${encode(text)}")
        return "mailto:${address?.takeIf(::isEmailAddress).orEmpty()}?${query.joinToString("&")}"
    }

    /**
     * An event for the share page (pair/src/page/share.ts), which turns it into a calendar file for the
     * phone's calendar app. [start] is a local date and time or, for an all-day event, a date (see
     * [parseWhen]); without [end] a timed event lasts an hour and an all-day one the day. Times stay
     * local without a zone: the phone is where the watch is. [zone] only fills Google Calendar's link,
     * which the page offers too.
     */
    fun event(title: String, start: When, end: When?, place: String?, details: String?, zone: ZoneId): JsonObject = buildJsonObject {
        put("title", title)
        when (start) {
            is When.Day -> {
                put("start", start.date.toString())
                put("end", ((end as? When.Day)?.date?.takeIf { it >= start.date } ?: start.date).toString())
            }
            is When.Time -> {
                put("start", start.time.format(MINUTES))
                put("end", ((end as? When.Time)?.time?.takeIf { it > start.time } ?: start.time.plusHours(1)).format(MINUTES))
            }
        }
        place?.takeIf { it.isNotBlank() }?.let { put("place", it) }
        details?.takeIf { it.isNotBlank() }?.let { put("details", it) }
        put("zone", zone.id)
    }

    /** The phone's maps app on [place] (geo: is what every maps app takes), from where the route is a tap away. */
    fun place(place: String): String = "geo:0,0?q=${encode(place)}"

    /** A date or a date and time the model passed, local to the watch. */
    sealed interface When {
        data class Day(val date: LocalDate) : When
        data class Time(val time: LocalDateTime) : When
    }

    /** "2026-11-02T15:00", "2026-11-02 15:00" (seconds allowed) or "2026-11-02"; null for anything else. */
    fun parseWhen(text: String?): When? {
        val trimmed = text?.trim()?.replace(' ', 'T')?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            if ('T' in trimmed) When.Time(LocalDateTime.parse(trimmed).withNano(0)) else When.Day(LocalDate.parse(trimmed))
        } catch (_: DateTimeParseException) {
            null
        }
    }

    /**
     * The digits WhatsApp's links want: the country code first, no plus, no leading zeros. Null when the
     * number carries no country code ("0171 …"), which the watch then completes for its country.
     */
    fun internationalDigits(number: String): String? {
        val compact = dialable(number)
        val digits = when {
            compact.startsWith("+") -> compact.drop(1)
            compact.startsWith("00") -> compact.drop(2)
            else -> return null
        }
        return digits.takeIf { it.length in 7..15 && it.all(Char::isDigit) }
    }

    /** The number without the spaces, dashes, dots, slashes and brackets people write it with. */
    fun dialable(number: String): String = number.filter { it.isDigit() || it == '+' }

    fun isEmailAddress(text: String): Boolean = EMAIL.matches(text)

    // URLEncoder encodes for forms; a "+" for a space stays a plus in sms: and mailto: links.
    private fun encode(text: String): String = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

    private val EMAIL = Regex("[^\\s@?&#/]+@[^\\s@?&#/]+\\.[^\\s@?&#/]+")
    private val MINUTES = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
}
