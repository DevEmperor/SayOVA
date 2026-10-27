package net.devemperor.parley

import net.devemperor.parley.data.KeyVault
import net.devemperor.parley.data.ParleySettings
import net.devemperor.parley.session.ToolRunner
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.YearMonth

class ToolRunnerTest {
    @Test
    fun `timer lengths read naturally`() {
        assertEquals("45 s", ToolRunner.formatDuration(45))
        assertEquals("5 min", ToolRunner.formatDuration(300))
        assertEquals("1 min 30 s", ToolRunner.formatDuration(90))
        assertEquals("2 h", ToolRunner.formatDuration(7200))
        assertEquals("1 h 15 min", ToolRunner.formatDuration(4500))
    }

    @Test
    fun `key hint shows only the last four characters`() {
        assertEquals("…cdef", KeyVault.hintOf("sk-0123456789abcdef"))
    }

    @Test
    fun `monthly cost resets with the month`() {
        val settings = ParleySettings(costMonth = "2026-09", costMonthUsd = 1.5)
        assertEquals(1.5, settings.monthUsd(YearMonth.of(2026, 9)), 0.0)
        assertEquals(0.0, settings.monthUsd(YearMonth.of(2026, 10)), 0.0)
    }
}
