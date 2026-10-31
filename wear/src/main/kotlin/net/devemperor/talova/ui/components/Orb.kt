package net.devemperor.talova.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.wear.compose.material3.Icon
import net.devemperor.talova.R
import net.devemperor.talova.session.Phase

/**
 * Talova's orb (see [CloudOrbView]): drifts when idle, churns with the microphone while listening, with
 * Talova's voice while speaking, spins while thinking or connecting, and tints at the end.
 *
 * @param phase null on the start screens (no conversation).
 * @param level microphone or speech loudness, 0..1.
 */
@Composable
fun Orb(
    phase: Phase?,
    level: Float,
    modifier: Modifier = Modifier,
    muted: Boolean = false,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        AndroidView(
            factory = ::CloudOrbView,
            modifier = Modifier.fillMaxSize(),
            update = { view ->
                view.setMode(
                    when (phase) {
                        null -> CloudOrbView.Mode.IDLE
                        Phase.CONNECTING, Phase.RECONNECTING, Phase.THINKING -> CloudOrbView.Mode.THINKING
                        Phase.LISTENING -> CloudOrbView.Mode.LISTENING
                        Phase.SPEAKING -> CloudOrbView.Mode.SPEAKING
                        Phase.ENDED -> CloudOrbView.Mode.DONE
                        Phase.ERROR -> CloudOrbView.Mode.ERROR
                    },
                )
                view.setLevel(if (muted) 0f else level)
                view.setPaused(muted)
            },
        )
        if (muted) {
            Icon(
                painterResource(R.drawable.ic_mic_off),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

/**
 * Hands the home screen's orb over to the conversation and back: where it was when a conversation
 * started from it, so the conversation's orb can grow out of it, and that the conversation's orb has
 * shrunk back onto it at the end (Wear's navigation has no shared elements).
 */
object OrbHandoff {
    /** The home orb's bounds in the window, kept current while the home screen shows it. */
    @Volatile var homeBounds: Rect? = null
    private var pending: Rect? = null
    private var landed = false

    /** A conversation starts from the home screen now. */
    fun launch() {
        pending = homeBounds
    }

    /** Where to grow from, once; null when the conversation did not start from the home orb. */
    fun take(): Rect? = pending.also { pending = null }

    /** The ended conversation's orb now sits where the home orb is. */
    fun land() {
        landed = true
    }

    /** Whether the home screen comes back under a landed orb, once. */
    fun takeLanded(): Boolean = landed.also { landed = false }
}
