package net.devemperor.parley.session

import android.content.Context
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The one place the screens, the tile and the service meet. At most one conversation runs at a time;
 * its state stays here after it ended until the screen has shown the outcome and calls [dismiss].
 */
object SessionHub {
    private val _state = MutableStateFlow<ConversationUiState?>(null)
    val state: StateFlow<ConversationUiState?> = _state.asStateFlow()

    // Holds the service's engine only while a conversation runs; the service clears it when it stops.
    @Suppress("StaticFieldLeak")
    @Volatile internal var engine: ConversationEngine? = null

    val running: Boolean get() = _state.value?.active == true

    /** Starts a conversation unless one is already running. Call only while an activity is visible. */
    fun start(context: Context, textOnly: Boolean, demo: Boolean = false) {
        if (running) return
        _state.value = ConversationUiState(textOnly = textOnly)
        ContextCompat.startForegroundService(context, SessionService.startIntent(context, textOnly, demo))
    }

    fun tapOrb() = engine?.tapOrb()
    fun toggleMute() = engine?.toggleMute()

    fun end(context: Context) {
        engine?.end() ?: run {
            // Still connecting (no engine yet): stop the service, which tidies up.
            context.startService(SessionService.stopIntent(context))
            if (_state.value?.active == true) _state.value = null
        }
    }

    /** The screen has shown the end of the conversation. */
    fun dismiss() {
        if (_state.value?.active == false) _state.value = null
    }

    internal fun publish(state: ConversationUiState) {
        _state.value = state
    }
}
