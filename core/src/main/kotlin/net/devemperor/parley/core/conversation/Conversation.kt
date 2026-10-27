package net.devemperor.parley.core.conversation

import kotlinx.serialization.Serializable

/** One conversation as kept in the history on the watch. */
@Serializable
data class Conversation(
    val id: String,
    val startedAt: Long,
    val endedAt: Long,
    val provider: String,
    val model: String,
    val turns: List<Turn>,
    val costUsd: Double = 0.0,
) {
    /** The first thing the user said, as the conversation's title in the list. */
    val title: String
        get() = turns.firstOrNull { it.role == Role.USER && it.text.isNotBlank() }?.text
            ?: turns.firstOrNull { it.text.isNotBlank() }?.text
            ?: ""
}

@Serializable
data class Turn(
    val role: Role,
    val text: String,
    val at: Long,
    /** The answer was cut short; [text] is what was actually heard. */
    val interrupted: Boolean = false,
)

@Serializable
enum class Role { USER, ASSISTANT, NOTE, TOOL }
