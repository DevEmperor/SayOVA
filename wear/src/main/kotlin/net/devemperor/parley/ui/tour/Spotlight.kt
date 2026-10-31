package net.devemperor.parley.ui.tour

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateRectAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.horizontalScrollAxisRange
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.MaterialTheme
import net.devemperor.parley.ui.components.screenWidth
import net.devemperor.parley.ui.theme.ParleyColors
import kotlin.math.abs

/** The controls a tour can point at; each says where it is with [tourTarget]. */
enum class TourTarget { HOME_ORB, HOME_LINE, HOME_INTERPRETER, HOME_HISTORY, HOME_SETTINGS, TALK_ORB, TALK_TEXT, TALK_SOUND, TALK_END }

/** Where each tour target is in the window, kept current while its screen shows it. */
object TourTargets {
    val bounds = mutableStateMapOf<TourTarget, Rect>()
}

/**
 * [visual]: the control's visible size where it is smaller than its touch area (round icon buttons
 * take at least 48 dp of taps around them), so the ring hugs the button rather than that area.
 */
fun Modifier.tourTarget(target: TourTarget, visual: Dp? = null): Modifier = composed {
    val visualPx = visual?.let { with(LocalDensity.current) { it.toPx() } }
    onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInWindow()
        TourTargets.bounds[target] = if (visualPx == null) bounds else Rect(bounds.center, visualPx / 2)
    }
}

class TourStep(val target: TourTarget, @param:StringRes val text: Int)

/**
 * A short tour over a screen: all of it dims but a ring around one control, with a few words about it
 * on the other half of the round screen and a dot for each step. The user turns the pages at their own
 * pace, like a pager: swiping left (or turning the bezel or crown clockwise, or a tap) goes on, swiping
 * right goes back; on from the last step or back from the first ends the tour ([onDone]), and so does
 * the system's back. The screen underneath gets no input meanwhile.
 */
@Composable
fun Spotlight(steps: List<TourStep>, onDone: () -> Unit) {
    var index by remember { mutableIntStateOf(0) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val done by rememberUpdatedState(onDone)
    val next = { if (index < steps.lastIndex) index++ else done() }
    val previous = { if (index > 0) index-- else done() }
    BackHandler(onBack = onDone)
    val step = steps[index]
    val density = LocalDensity.current
    val margin = with(density) { RING_MARGIN.toPx() }
    val swipePx = with(density) { SWIPE.toPx() }
    val rotaryPx = with(density) { ROTARY_STEP.toPx() }
    var rotary by remember { mutableFloatStateOf(0f) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val target = TourTargets.bounds[step.target]?.translate(-origin)?.inflate(margin) ?: Rect.Zero
    // From one control to the next the ring travels instead of jumping.
    val ring by animateRectAsState(target, label = "ring")
    val teal = ParleyColors.TealBright
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.positionInWindow() }
            // Tells the watch that a swipe to the right turns a page here rather than leaving the app or
            // the screen (Wear dismisses a screen with that swipe unless something can scroll back).
            .semantics { horizontalScrollAxisRange = ScrollAxisRange(value = { index + 1f }, maxValue = { steps.size + 1f }) }
            .pointerInput(steps) { detectTapGestures { next() } }
            .pointerInput(steps) {
                var dragged = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dragged = 0f },
                    onDragEnd = {
                        when {
                            dragged < -swipePx -> next()
                            dragged > swipePx -> previous()
                        }
                    },
                ) { change, amount ->
                    change.consume()
                    dragged += amount
                }
            }
            .onRotaryScrollEvent { event ->
                // One page per bezel click; turning the other way starts over.
                val px = event.verticalScrollPixels
                rotary = if (rotary * px < 0f) px else rotary + px
                if (abs(rotary) >= rotaryPx) {
                    if (rotary > 0f) next() else previous()
                    rotary = 0f
                }
                true
            }
            .focusRequester(focus)
            .focusable(),
    ) {
        Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
            drawRect(Color.Black.copy(alpha = SCRIM_ALPHA))
            if (ring.width > 0f) {
                // Round controls get a circle, the cost line a pill.
                val corner = CornerRadius(minOf(ring.width, ring.height) / 2)
                drawRoundRect(Color.Transparent, ring.topLeft, ring.size, corner, blendMode = BlendMode.Clear)
                drawRoundRect(teal, ring.topLeft, ring.size, corner, style = Stroke(RING_WIDTH.toPx()))
            }
        }
        val height = maxHeight
        // The words go on the other side of the ring, between it and the round screen's edge (below the
        // clock at the top), and get smaller where that space is short.
        val below = target.center.y < with(density) { height.toPx() } / 2
        val ringTop = with(density) { target.top.toDp() }
        val ringBottom = with(density) { target.bottom.toDp() }
        val (top, bottom) = if (below) ringBottom + GAP to height * 0.85f else height * 0.115f to ringTop - GAP
        val style = MaterialTheme.typography.bodyMedium
        Box(
            Modifier
                .offset(y = top)
                .height((bottom - top).coerceAtLeast(0.dp))
                .fillMaxWidth()
                .padding(horizontal = screenWidth() * 0.15f),
            contentAlignment = if (below) Alignment.TopCenter else Alignment.BottomCenter,
        ) {
            BasicText(
                stringResource(step.text),
                style = style.merge(color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center),
                autoSize = TextAutoSize.StepBased(minFontSize = MIN_TEXT, maxFontSize = style.fontSize, stepSize = 0.5.sp),
            )
        }
        // Always at the bottom edge, where they take no room from the words.
        Dots(steps.size, index, Modifier.align(Alignment.BottomCenter).padding(bottom = height * 0.05f))
    }
}

@Composable
private fun Dots(count: Int, current: Int, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(count) { i ->
            Box(
                Modifier
                    .size(5.dp)
                    .background(if (i == current) ParleyColors.TealBright else Color.White.copy(alpha = 0.3f), CircleShape),
            )
        }
    }
}

private const val SCRIM_ALPHA = 0.82f
private val RING_MARGIN = 5.dp
private val RING_WIDTH = 2.dp
private val GAP = 8.dp
private val MIN_TEXT = 10.sp
/** How far a swipe must go to turn a page; shorter ones count as nothing. */
private val SWIPE = 24.dp
/** Bezel or crown travel per page: one click of the Galaxy Watch bezel, as for the volume. */
private val ROTARY_STEP = 12.dp
