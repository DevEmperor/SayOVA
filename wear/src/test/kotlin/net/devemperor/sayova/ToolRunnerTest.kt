package net.devemperor.sayova

import net.devemperor.sayova.data.KeyVault
import net.devemperor.sayova.session.ToolRunner
import org.junit.Assert.assertEquals
import org.junit.Test

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
}
