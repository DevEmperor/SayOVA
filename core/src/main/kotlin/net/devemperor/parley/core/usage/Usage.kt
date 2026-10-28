package net.devemperor.parley.core.usage

import kotlinx.serialization.Serializable
import net.devemperor.parley.core.conversation.Conversation
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * How much was talked and spent on one day. Kept on the watch apart from the history, so the
 * statistics stay complete when the history is short or switched off; nothing in it says what was
 * talked about.
 */
@Serializable
data class UsageDay(
    /** ISO date, yyyy-MM-dd, in the watch's time zone at the time. */
    val date: String,
    val costUsd: Double = 0.0,
    val talkMs: Long = 0,
    val conversations: Int = 0,
) {
    val day: LocalDate get() = LocalDate.parse(date)
}

data class UsageTotal(val costUsd: Double = 0.0, val talkMs: Long = 0, val conversations: Int = 0) {
    operator fun plus(other: UsageTotal) =
        UsageTotal(costUsd + other.costUsd, talkMs + other.talkMs, conversations + other.conversations)

    val costPerConversation: Double get() = if (conversations > 0) costUsd / conversations else 0.0
    val costPerMinute: Double get() = if (talkMs > 0) costUsd / (talkMs / 60_000.0) else 0.0
}

object Usage {

    /** [days] with one more conversation on [date]; one entry per date, oldest first. */
    fun add(days: List<UsageDay>, date: LocalDate, costUsd: Double, talkMs: Long, conversations: Int = 1): List<UsageDay> {
        val key = date.toString()
        val existing = days.firstOrNull { it.date == key }
        val updated = (existing ?: UsageDay(key)).let {
            it.copy(costUsd = it.costUsd + costUsd, talkMs = it.talkMs + talkMs, conversations = it.conversations + conversations)
        }
        return (days.filter { it.date != key } + updated).sortedBy { it.date }
    }

    /** The days the saved conversations were held on: where the statistics start on a watch that had none. */
    fun fromConversations(conversations: List<Conversation>, zone: ZoneId): List<UsageDay> =
        conversations.fold(emptyList()) { days, c ->
            add(days, Instant.ofEpochMilli(c.startedAt).atZone(zone).toLocalDate(), c.costUsd, (c.endedAt - c.startedAt).coerceAtLeast(0))
        }

    fun total(days: List<UsageDay>): UsageTotal =
        days.fold(UsageTotal()) { sum, d -> sum + UsageTotal(d.costUsd, d.talkMs, d.conversations) }

    fun month(days: List<UsageDay>, month: YearMonth): UsageTotal =
        total(days.filter { YearMonth.from(it.day) == month })

    /** Every month with any use, newest first. */
    fun months(days: List<UsageDay>): List<Pair<YearMonth, UsageTotal>> =
        days.groupBy { YearMonth.from(it.day) }
            .map { (month, list) -> month to total(list) }
            .sortedByDescending { it.first }

    /** The last [count] days up to [today], oldest first, days without use included as zero. */
    fun lastDays(days: List<UsageDay>, today: LocalDate, count: Int): List<Pair<LocalDate, UsageTotal>> {
        val byDate = days.associateBy { it.date }
        return (count - 1 downTo 0).map { back ->
            val date = today.minusDays(back.toLong())
            date to (byDate[date.toString()]?.let { UsageTotal(it.costUsd, it.talkMs, it.conversations) } ?: UsageTotal())
        }
    }

    /** "<1 min", "42 min", "3 h 05 min", with no-break spaces so a figure never parts from its unit. */
    fun formatDuration(ms: Long): String {
        val minutes = ms / 60_000
        return when {
            minutes < 1 -> "<1\u00A0min"
            minutes < 60 -> "$minutes\u00A0min"
            else -> "%d\u00A0h %02d\u00A0min".format(minutes / 60, minutes % 60)
        }
    }
}
