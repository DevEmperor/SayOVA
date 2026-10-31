package net.devemperor.sayova.session

import net.devemperor.sayova.core.conversation.Role
import net.devemperor.sayova.core.session.SessionError
import net.devemperor.sayova.core.tools.WatchFeature
import net.devemperor.sayova.net.Transport

enum class Phase(val label: String) {
    CONNECTING("Connecting"),
    LISTENING("Listening"),
    THINKING("Thinking"),
    SPEAKING("Speaking"),
    RECONNECTING("Reconnecting"),
    ENDED("Ended"),
    ERROR("Problem"),
}

/**
 * One line of the live transcript. [spokenChars] is how much of an answer has been heard so far: the
 * screen shows that part bright and the rest dimmed, like a karaoke line.
 */
data class UiTurn(
    val key: String,
    val role: Role,
    val text: String,
    val spokenChars: Int = text.length,
    val interrupted: Boolean = false,
)

data class ConversationUiState(
    val phase: Phase = Phase.CONNECTING,
    val turns: List<UiTurn> = emptyList(),
    val micLevel: Float = 0f,
    val speechLevel: Float = 0f,
    val elapsedMs: Long = 0,
    val costUsd: Double = 0.0,
    val showCost: Boolean = true,
    val transport: Transport = Transport.NONE,
    val muted: Boolean = false,
    /** Sayova's voice is switched off; answers play silently and the screen shows the transcript. */
    val soundOff: Boolean = false,
    val pushToTalk: Boolean = false,
    /** Push-to-talk: the microphone is open right now. */
    val talking: Boolean = false,
    /** Sayova is speaking over the watch speaker, so the microphone is closed: a tap interrupts. */
    val halfDuplex: Boolean = false,
    val providerLabel: String = "",
    /** Sayova interprets between two languages, shown in short ("DE ⇄ ES"); null in a normal conversation. */
    val interpreting: String? = null,
    /** A short, temporary hint ("Timer set", "Searching the web…"). */
    val notice: String? = null,
    /** The watch feature in use right now (location, heart rate …), shown with its icon. */
    val activeFeature: WatchFeature? = null,
    /** Sayova speaks as a call (see CallAudio): its volume is the call volume. */
    val callAudio: Boolean = false,
    val error: SessionError? = null,
    val saved: Boolean = false,
    /** The introduction's scripted sample (see SampleConversation), marked as such on screen. */
    val sample: Boolean = false,
) {
    val active: Boolean get() = phase != Phase.ENDED && phase != Phase.ERROR
}
