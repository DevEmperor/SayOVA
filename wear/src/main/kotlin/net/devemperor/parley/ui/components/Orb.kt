package net.devemperor.parley.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Icon
import net.devemperor.parley.R
import net.devemperor.parley.session.Phase
import net.devemperor.parley.ui.theme.ParleyColors
import kotlin.math.sin

/**
 * The living version of Parley's mark: a teal-to-violet sphere with three voice bars. It breathes
 * when idle, follows the microphone while listening, circles while thinking and moves with the voice
 * while speaking. Its glow takes the colour of the state.
 *
 * @param phase null on the start screen (no conversation).
 * @param level microphone or speech loudness, 0..1.
 */
@Composable
fun Orb(
    phase: Phase?,
    level: Float,
    modifier: Modifier = Modifier,
    muted: Boolean = false,
) {
    val transition = rememberInfiniteTransition(label = "orb")
    val time by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 4_800, easing = LinearEasing), RepeatMode.Restart),
        label = "time",
    )
    val smoothLevel by animateFloatAsState(
        targetValue = if (muted) 0f else level,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow),
        label = "level",
    )
    val glow by animateColorAsState(
        targetValue = if (muted) ParleyColors.Muted else ParleyColors.forPhase(phase),
        animationSpec = tween(durationMillis = 400),
        label = "glow",
    )
    val dim by animateFloatAsState(
        targetValue = when (phase) {
            Phase.CONNECTING, Phase.RECONNECTING, Phase.ENDED -> 0.55f
            else -> if (muted) 0.6f else 1f
        },
        animationSpec = tween(durationMillis = 400),
        label = "dim",
    )

    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2f * 0.62f
            val breathe = (sin(time * 2 * Math.PI.toFloat()) + 1f) / 2f
            val active = phase == Phase.LISTENING || phase == Phase.SPEAKING
            val pulse = if (active) smoothLevel else breathe * 0.25f
            val r = radius * (1f + 0.06f * pulse)

            // Glow
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(glow.copy(alpha = 0.42f * dim * (0.6f + 0.4f * pulse)), Color.Transparent),
                    center = center,
                    radius = r * 1.75f,
                ),
                radius = r * 1.75f,
            )

            // Sphere
            drawCircle(
                brush = Brush.linearGradient(
                    colors = listOf(ParleyColors.TealBright.copy(alpha = dim), ParleyColors.Teal.copy(alpha = dim), ParleyColors.Violet.copy(alpha = dim)),
                    start = Offset(center.x - r, center.y - r),
                    end = Offset(center.x + r, center.y + r),
                ),
                radius = r,
            )
            // Soft highlight, upper left
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White.copy(alpha = 0.28f * dim), Color.Transparent),
                    center = Offset(center.x - r * 0.35f, center.y - r * 0.4f),
                    radius = r * 0.7f,
                ),
                radius = r * 0.7f,
                center = Offset(center.x - r * 0.35f, center.y - r * 0.4f),
            )

            when (phase) {
                Phase.THINKING, Phase.CONNECTING, Phase.RECONNECTING -> spinner(r, time, glow)
                Phase.SPEAKING -> ring(r, smoothLevel, glow)
                Phase.LISTENING -> if (smoothLevel > 0.05f) ring(r, smoothLevel * 0.8f, glow)
                else -> Unit
            }

            if (!muted) bars(r, time, if (active) smoothLevel else 0f, phase == Phase.SPEAKING)
        }
        if (muted) {
            Icon(
                painterResource(R.drawable.ic_mic_off),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

private fun DrawScope.spinner(r: Float, time: Float, color: Color) {
    val ringRadius = r * 1.22f
    rotate(degrees = time * 720f) {
        drawArc(
            brush = Brush.sweepGradient(listOf(Color.Transparent, color, ParleyColors.TealBright), center),
            startAngle = 0f,
            sweepAngle = 280f,
            useCenter = false,
            topLeft = Offset(center.x - ringRadius, center.y - ringRadius),
            size = Size(ringRadius * 2, ringRadius * 2),
            style = Stroke(width = r * 0.07f, cap = StrokeCap.Round),
        )
    }
}

private fun DrawScope.ring(r: Float, level: Float, color: Color) {
    drawCircle(
        color = color.copy(alpha = 0.35f + 0.5f * level),
        radius = r * (1.14f + 0.12f * level),
        style = Stroke(width = r * (0.03f + 0.07f * level)),
    )
}

/** The three bars of the mark; they dance with [level] and sit still at the logo's proportions. */
private fun DrawScope.bars(r: Float, time: Float, level: Float, speaking: Boolean) {
    val width = r * 0.17f
    val base = floatArrayOf(0.42f, 0.80f, 0.42f)
    val spacing = r * 0.36f
    for (i in 0..2) {
        val wobble = if (level > 0f) {
            val phase = time * (if (speaking) 22f else 16f) + i * 1.7f
            0.55f + 0.45f * ((sin(phase) + 1f) / 2f)
        } else {
            1f
        }
        val height = (r * base[i] * (1f + level * 0.9f * wobble)).coerceAtMost(r * 1.25f)
        val x = center.x + (i - 1) * spacing - width / 2f
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(x, center.y - height / 2f),
            size = Size(width, height),
            cornerRadius = CornerRadius(width / 2f),
        )
    }
}
