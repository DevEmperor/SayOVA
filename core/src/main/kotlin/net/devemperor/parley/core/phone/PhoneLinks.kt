package net.devemperor.parley.core.phone

import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Links that open something prepared on the paired phone for the user to send or start there: a
 * message, an e-mail, a calendar entry or a route. A watch can only open a browsable link on the
 * phone, so each is one the phone's apps know: wa.me for WhatsApp, sms: and mailto: for the messages
 * and mail apps, Google Calendar's new-event page and Google Maps' directions.
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
     * Google Calendar's page for a new event, filled in; the user saves it there. [start] is a local
     * date and time or, for an all-day event, a date (see [parseWhen]); without [end] a timed event
     * lasts an hour and an all-day one the day.
     */
    fun calendarEvent(title: String, start: When, end: When?, place: String?, details: String?, zone: ZoneId): String {
        val dates = when (start) {
            is When.Day -> {
                val last = (end as? When.Day)?.date?.takeIf { it >= start.date } ?: start.date
                // Google takes the day after the last as the end.
                "${start.date.format(DAY)}/${last.plusDays(1).format(DAY)}"
            }
            is When.Time -> {
                val until = (end as? When.Time)?.time?.takeIf { it > start.time } ?: start.time.plusHours(1)
                "${start.time.format(TIME)}/${until.format(TIME)}"
            }
        }
        return buildString {
            append("https://calendar.google.com/calendar/render?action=TEMPLATE")
            append("&text=").append(encode(title))
            append("&dates=").append(dates)
            append("&ctz=").append(encode(zone.id))
            place?.takeIf { it.isNotBlank() }?.let { append("&location=").append(encode(it)) }
            details?.takeIf { it.isNotBlank() }?.let { append("&details=").append(encode(it)) }
        }
    }

    /** Google Maps' route from where the phone is to [destination]; [mode] is one of [TRAVEL_MODES] or left to Maps. */
    fun route(destination: String, mode: String?): String = buildString {
        append("https://www.google.com/maps/dir/?api=1&destination=").append(encode(destination))
        mode?.lowercase()?.takeIf { it in TRAVEL_MODES }?.let { append("&travelmode=").append(it) }
    }

    val TRAVEL_MODES = setOf("driving", "walking", "bicycling", "transit")

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
    private val DAY = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
}
