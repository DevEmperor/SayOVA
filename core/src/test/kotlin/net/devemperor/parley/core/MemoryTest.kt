package net.devemperor.parley.core

import net.devemperor.parley.core.memory.Fact
import net.devemperor.parley.core.memory.Memory
import net.devemperor.parley.core.prompt.AnswerLanguage
import net.devemperor.parley.core.prompt.Instructions
import net.devemperor.parley.core.prompt.Persona
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class MemoryTest {

    private fun saved(change: Memory.Change) = change as Memory.Change.Saved

    @Test
    fun `facts are numbered and keep their number after others are forgotten`() {
        var facts = saved(Memory.remember(emptyList(), "  Is   vegetarian ", null, 1)).facts
        facts = saved(Memory.remember(facts, "Lives in Giessen", null, 2)).facts
        assertEquals(listOf(Fact(1, "Is vegetarian", 1), Fact(2, "Lives in Giessen", 2)), facts)
        facts = (Memory.forget(facts, 1) as Memory.Change.Forgotten).facts
        facts = saved(Memory.remember(facts, "Has a dog called Bruno", null, 3)).facts
        assertEquals(listOf(2, 3), facts.map { it.number })
    }

    @Test
    fun `an update replaces the fact in place instead of adding one`() {
        val facts = listOf(Fact(1, "Lives in Giessen", 1), Fact(2, "Is vegetarian", 1))
        val change = saved(Memory.remember(facts, "Lives in Wetzlar", 1, 5))
        assertEquals(listOf(Fact(1, "Lives in Wetzlar", 5), Fact(2, "Is vegetarian", 1)), change.facts)
        assertEquals("Lives in Giessen", change.replaced?.text)
    }

    @Test
    fun `the same fact twice is kept once`() {
        val facts = listOf(Fact(1, "Is vegetarian", 1))
        assertTrue(Memory.remember(facts, "is Vegetarian", null, 2) is Memory.Change.Known)
        assertTrue(Memory.remember(facts, "Is vegetarian", 1, 2) is Memory.Change.Known)
    }

    @Test
    fun `a full memory refuses instead of dropping a fact, but still takes updates`() {
        val full = (1..Memory.LIMIT).map { Fact(it, "Fact $it", 1) }
        val refused = Memory.remember(full, "One more", null, 2)
        assertTrue(refused is Memory.Change.Refused && "forget_fact" in refused.reason)
        assertEquals(full, refused.facts)
        assertEquals(Memory.LIMIT, saved(Memory.remember(full, "Fact 3, updated", 3, 2)).facts.size)
    }

    @Test
    fun `empty, too long and unknown numbers are refused`() {
        val facts = listOf(Fact(1, "Is vegetarian", 1))
        assertTrue(Memory.remember(facts, "   ", null, 2) is Memory.Change.Refused)
        assertTrue(Memory.remember(facts, "x".repeat(Memory.MAX_LENGTH + 1), null, 2) is Memory.Change.Refused)
        assertTrue(Memory.remember(facts, "Lives in Giessen", 7, 2) is Memory.Change.Refused)
        assertTrue(Memory.forget(facts, 7) is Memory.Change.Refused)
    }

    @Test
    fun `the instructions carry the facts only while the memory is on`() {
        val now = ZonedDateTime.of(2026, 10, 28, 12, 0, 0, 0, ZoneId.of("Europe/Berlin"))
        fun build(memory: List<Fact>?) = Instructions.build(Persona.FRIENDLY, "", AnswerLanguage.AUTO, now, canSearch = false, memory = memory)

        val on = build(listOf(Fact(2, "Lives in Giessen", 1), Fact(5, "Is vegetarian", 1)))
        assertTrue("What you remember (number, fact):\n[2] Lives in Giessen\n[5] Is vegetarian" in on)
        assertTrue("remember facts about the user" in on)
        assertTrue("save_note" in on)

        assertTrue("You remember nothing about the user yet." in build(emptyList()))

        val off = build(null)
        assertTrue("Memory is off" in off)
        assertFalse("remember facts about the user" in off)
        assertFalse("[2]" in off)
    }
}
