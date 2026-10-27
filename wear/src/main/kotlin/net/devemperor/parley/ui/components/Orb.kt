package net.devemperor.parley.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.wear.compose.material3.Icon
import net.devemperor.parley.R
import net.devemperor.parley.session.Phase

/**
 * Parley's orb (see [CloudOrbView]): drifts when idle, churns with the microphone while listening, with
 * Parley's voice while speaking, spins while thinking or connecting, and tints at the end.
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
