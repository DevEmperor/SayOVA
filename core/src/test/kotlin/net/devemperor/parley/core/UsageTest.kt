package net.devemperor.parley.core

import net.devemperor.parley.core.conversation.Conversation
import net.devemperor.parley.core.usage.Usage
import net.devemperor.parley.core.usage.UsageDay
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime

class UsageTest {

    @Test
    fun `conversations on the same day add up in one entry`() {
        val day = LocalDate.of(2026, 10, 28)
        val days = Usage.add(Usage.add(emptyList(), day, 0.02, 60_000), day, 0.01, 30_000)
        assertEquals(listOf(UsageDay("2026-10-28", 0.03, 90_000, 2)), days.map { it.copy(costUsd = Math.round(it.costUsd * 100) / 100.0) })
    }

    @Test
    fun `months are summed and listed newest first`() {
        var days = emptyList<UsageDay>()
        days = Usage.add(days, LocalDate.of(2026, 9, 30), 0.10, 120_000)
        days = Usage.add(days, LocalDate.of(2026, 10, 1), 0.05, 60_000)
        days = Usage.add(days, LocalDate.of(2026, 10, 27), 0.05, 60_000)
        val months = Usage.months(days)
        assertEquals(listOf(YearMonth.of(2026, 10), YearMonth.of(2026, 9)), months.map { it.first })
        assertEquals(2, months[0].second.conversations)
        assertEquals(120_000, months[0].second.talkMs)
        assertEquals(0.05, months[0].second.costPerConversation, 1e-9)
        assertEquals(0.05, months[0].second.costPerMinute, 1e-9)
    }

    @Test
    fun `the last days include the quiet ones`() {
        val days = Usage.add(emptyList(), LocalDate.of(2026, 10, 26), 0.01, 60_000)
        val last = Usage.lastDays(days, LocalDate.of(2026, 10, 28), 3)
        assertEquals(listOf(LocalDate.of(2026, 10, 26), LocalDate.of(2026, 10, 27), LocalDate.of(2026, 10, 28)), last.map { it.first })
        assertEquals(listOf(1, 0, 0), last.map { it.second.conversations })
    }

    @Test
    fun `the history seeds the statistics in the watch's time zone`() {
        val zone = ZoneId.of("Europe/Berlin")
        val lateEvening = ZonedDateTime.of(2026, 10, 27, 23, 30, 0, 0, zone).toInstant().toEpochMilli()
        val conversation = Conversation("a", lateEvening, lateEvening + 300_000, "gemini", "m", emptyList(), 0.04)
        assertEquals(listOf(UsageDay("2026-10-27", 0.04, 300_000, 1)), Usage.fromConversations(listOf(conversation), zone))
    }

    @Test
    fun `durations read like a person would say them`() {
        assertEquals("<1\u00A0min", Usage.formatDuration(59_000))
        assertEquals("42\u00A0min", Usage.formatDuration(42 * 60_000L))
        assertEquals("3\u00A0h 05\u00A0min", Usage.formatDuration((3 * 60 + 5) * 60_000L))
    }
}
