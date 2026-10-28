package net.devemperor.parley.core.memory

import kotlinx.serialization.Serializable

/**
 * A lasting fact about the user that Parley was asked to remember. [number] stays the same for the
 * fact's life, so the model can name it to replace or forget it even after others were removed.
 */
@Serializable
data class Fact(val number: Int, val text: String, val savedAt: Long)

/**
 * The rules of Parley's memory, kept apart from storage so they can be tested: at most [LIMIT] short
 * facts, each given to every conversation, so the user decides what goes and nothing leaves silently.
 */
object Memory {
    const val LIMIT = 30
    const val MAX_LENGTH = 150

    sealed interface Change {
        val facts: List<Fact>

        /** [fact] was saved; [replaced] is the earlier fact it took the place of. */
        data class Saved(override val facts: List<Fact>, val fact: Fact, val replaced: Fact? = null) : Change

        /** The same fact was already known; nothing changed. */
        data class Known(override val facts: List<Fact>, val fact: Fact) : Change

        data class Forgotten(override val facts: List<Fact>, val fact: Fact) : Change

        /** Nothing changed; [reason] tells the model what to do instead. */
        data class Refused(override val facts: List<Fact>, val reason: String) : Change
    }

    fun remember(facts: List<Fact>, text: String, replaces: Int?, now: Long): Change {
        val clean = text.trim().replace(Regex("\\s+"), " ")
        if (clean.isEmpty()) return Change.Refused(facts, "The fact is empty.")
        if (clean.length > MAX_LENGTH) return Change.Refused(facts, "Too long: keep the fact under $MAX_LENGTH characters and try again.")
        val old = replaces?.let { number ->
            facts.firstOrNull { it.number == number } ?: return Change.Refused(facts, "There is no fact number $number.")
        }
        facts.firstOrNull { it.text.equals(clean, ignoreCase = true) && it != old }?.let { return Change.Known(facts, it) }
        if (old == null && facts.size >= LIMIT) {
            return Change.Refused(
                facts,
                "The memory is full ($LIMIT facts). Ask the user which fact to forget, call forget_fact, then save this one.",
            )
        }
        if (old != null && old.text == clean) return Change.Known(facts, old)
        // A replaced fact keeps its number and place; a new one goes last, numbered after all others.
        val fact = Fact(old?.number ?: ((facts.maxOfOrNull { it.number } ?: 0) + 1), clean, now)
        val updated = if (old != null) facts.map { if (it.number == old.number) fact else it } else facts + fact
        return Change.Saved(updated, fact, old)
    }

    fun forget(facts: List<Fact>, number: Int): Change {
        val fact = facts.firstOrNull { it.number == number } ?: return Change.Refused(facts, "There is no fact number $number.")
        return Change.Forgotten(facts.filter { it.number != number }, fact)
    }
}
