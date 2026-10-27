package net.devemperor.parley.ui.conversation

import android.os.SystemClock
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import androidx.wear.compose.material3.curvedText
import androidx.wear.compose.material3.timeTextCurvedText
import androidx.wear.compose.material3.timeTextSeparator
import kotlinx.coroutines.delay
import net.devemperor.parley.R
import net.devemperor.parley.core.conversation.Role as TurnRole
import net.devemperor.parley.core.cost.CostMeter
import net.devemperor.parley.core.session.ErrorKind
import net.devemperor.parley.net.Transport
import net.devemperor.parley.session.ConversationUiState
import net.devemperor.parley.session.Phase
import net.devemperor.parley.session.UiTurn
import net.devemperor.parley.ui.components.KeepScreenOn
import net.devemperor.parley.ui.components.MessageScreen
import net.devemperor.parley.ui.components.Orb
import net.devemperor.parley.ui.components.screenWidth
import net.devemperor.parley.ui.theme.ParleyColors

/**
 * The live conversation: status on the bezel, the orb as the one control, one caption line under it,
 * the red end button at the bottom with the text button beside it. By default only the orb is shown,
 * large and centred, pulsing with whoever speaks; with the transcript on, the orb moves up and the
 * transcript scrolls below it, the part of an answer already heard bright, the rest dim. The last
 * choice is remembered ([showTranscript]); silent mode always shows the text, its only output.
 *
 * The screen deliberately does not hand the transcript's scroll state to the scaffold: Wear would
 * then scroll the clock and status away and shrink the end button exactly while Parley speaks.
 */
@Composable
fun ConversationScreen(
    state: ConversationUiState,
    showTranscript: Boolean,
    onToggleTranscript: () -> Unit,
    onTapOrb: () -> Unit,
    onEnd: () -> Unit,
    onDone: () -> Unit,
    onRetry: () -> Unit,
    onFixProvider: () -> Unit,
) {
    KeepScreenOn(state.active)

    if (state.phase == Phase.ERROR && state.error != null) {
        ErrorContent(state, onRetry = onRetry, onFixProvider = onFixProvider, onClose = onDone)
        return
    }
    if (state.phase == Phase.ENDED) {
        LaunchedEffect(Unit) {
            delay(1_400)
            onDone()
        }
    }

    val statusColor = ParleyColors.forPhase(state.phase)
    val status = statusText(state)
    val transcript = showTranscript || state.textOnly
    // No edge button: a small red hang-up button leaves the content most of the screen and is
    // recognisable at a glance, like the one on a phone call.
    ScreenScaffold(
        timeText = {
            TimeText { time ->
                timeTextCurvedText(time)
                timeTextSeparator()
                curvedText(status, color = statusColor)
            }
        },
    ) { _ ->
        val width = screenWidth()
        val orbSize by animateDpAsState(if (transcript) width * 0.2f else width * 0.46f, label = "orb")
        Column(
            Modifier.fillMaxSize().padding(bottom = width * 0.03f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(width * 0.12f))
            // The orb keeps its place in the composition either way, so switching only resizes it.
            if (!transcript) Spacer(Modifier.weight(1f))
            val orbLabel = orbDescription(state)
            Box(
                Modifier
                    .size(orbSize)
                    .semantics { contentDescription = orbLabel }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        enabled = state.active,
                        onClick = onTapOrb,
                    ),
            ) {
                val level = if (state.phase == Phase.SPEAKING) state.speechLevel else state.micLevel
                Orb(
                    phase = state.phase,
                    level = level,
                    muted = state.muted || (state.pushToTalk && !state.talking && state.phase == Phase.LISTENING),
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (!transcript) Spacer(Modifier.height(6.dp))
            Caption(state)
            if (transcript) {
                Transcript(state, width, Modifier.weight(1f).fillMaxWidth())
            } else {
                Spacer(Modifier.weight(1f))
            }
            Controls(
                state = state,
                width = width,
                transcript = transcript,
                onToggleTranscript = onToggleTranscript.takeUnless { state.textOnly },
                onEnd = onEnd,
            )
        }
    }
}

/**
 * The red end button in the middle and, left of it, the text button: dim with the text hidden, lit
 * with it shown. [onToggleTranscript] is null in silent mode, where the text cannot be hidden. The
 * empty slot on the right keeps the end button centred.
 */
@Composable
private fun Controls(state: ConversationUiState, width: Dp, transcript: Boolean, onToggleTranscript: (() -> Unit)?, onEnd: () -> Unit) {
    val side = width * 0.12f
    Row(
        horizontalArrangement = Arrangement.spacedBy(width * 0.04f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(side)) {
            if (onToggleTranscript != null) {
                FilledIconButton(
                    onClick = onToggleTranscript,
                    colors = if (transcript) {
                        IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    } else {
                        IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Icon(
                        painterResource(if (transcript) R.drawable.ic_subtitles else R.drawable.ic_subtitles_off),
                        contentDescription = stringResource(if (transcript) R.string.action_hide_text else R.string.action_show_text),
                        modifier = Modifier.size(width * 0.07f),
                    )
                }
            }
        }
        FilledIconButton(
            onClick = onEnd,
            enabled = state.active,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = ParleyColors.HangUp, contentColor = Color.White),
            modifier = Modifier.size(width * 0.15f),
        ) {
            Icon(painterResource(R.drawable.ic_call_end), contentDescription = stringResource(R.string.action_end), modifier = Modifier.size(width * 0.085f))
        }
        Spacer(Modifier.size(side))
    }
}

/**
 * The conversation so far. While Parley speaks it follows the spoken words, keeping the line being
 * said near the top: the text of an answer arrives seconds before its voice, and following the end of
 * the text would scroll the bright, heard part out of sight. Otherwise it follows the newest line.
 * Either way it leaves the user alone for a few seconds after they scrolled with the crown or a swipe.
 * The top edge fades, so lines leave under the caption rather than being cut.
 */
@Composable
private fun Transcript(state: ConversationUiState, width: Dp, modifier: Modifier) {
    val scroll = rememberScrollState()
    val focus = remember { FocusRequester() }
    var scrollingOurselves by remember { mutableStateOf(false) }
    var userScrolledAt by remember { mutableLongStateOf(0L) }
    // Where the line being spoken starts, in the column's own coordinates; -1 when nobody speaks.
    var spokenLineY by remember { mutableFloatStateOf(-1f) }
    val speaking = state.phase == Phase.SPEAKING && !state.textOnly
    val leadPx = with(LocalDensity.current) { LEAD.toPx() }
    LaunchedEffect(scroll.isScrollInProgress) {
        if (scroll.isScrollInProgress && !scrollingOurselves) userScrolledAt = SystemClock.elapsedRealtime()
    }
    LaunchedEffect(speaking) {
        snapshotFlow { if (speaking && spokenLineY >= 0f) spokenLineY - leadPx else scroll.maxValue.toFloat() }
            .collect { target ->
                if (SystemClock.elapsedRealtime() - userScrolledAt > FOLLOW_PAUSE_MS) {
                    scrollingOurselves = true
                    try {
                        scroll.animateScrollTo(target.toInt().coerceIn(0, scroll.maxValue))
                    } finally {
                        scrollingOurselves = false
                    }
                }
            }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Column(
        modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val fade = (FADE.toPx() / size.height).coerceIn(0f, 0.5f)
                drawRect(
                    Brush.verticalGradient(0f to Color.Transparent, fade to Color.Black, 1f - fade to Color.Black, 1f to Color.Transparent),
                    blendMode = BlendMode.DstIn,
                )
            }
            .rotaryScrollable(RotaryScrollableDefaults.behavior(scroll), focus)
            .verticalScroll(scroll)
            .padding(horizontal = width * 0.11f, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (state.turns.isEmpty()) {
            EmptyHint(state)
        } else {
            val lastAssistant = state.turns.indexOfLast { it.role == TurnRole.ASSISTANT }
            state.turns.forEachIndexed { index, turn ->
                TurnLine(
                    turn,
                    latest = index >= lastAssistant,
                    onSpokenLine = if (index == lastAssistant && speaking) ({ y -> spokenLineY = y }) else null,
                )
            }
        }
    }
}

@Composable
private fun statusText(state: ConversationUiState): String = when {
    state.phase == Phase.LISTENING && state.muted -> stringResource(R.string.status_muted)
    state.phase == Phase.LISTENING && state.pushToTalk && !state.talking -> stringResource(R.string.status_tap_to_talk)
    else -> when (state.phase) {
        Phase.CONNECTING -> stringResource(R.string.status_connecting)
        Phase.LISTENING -> stringResource(R.string.status_listening)
        Phase.THINKING -> stringResource(R.string.status_thinking)
        Phase.SPEAKING -> stringResource(if (state.textOnly) R.string.status_writing else R.string.status_speaking)
        Phase.RECONNECTING -> stringResource(R.string.status_reconnecting)
        Phase.ENDED -> stringResource(R.string.status_ended)
        Phase.ERROR -> stringResource(R.string.status_problem)
    }
}

@Composable
private fun orbDescription(state: ConversationUiState): String = when {
    state.phase == Phase.SPEAKING -> stringResource(R.string.orb_interrupt)
    state.pushToTalk -> stringResource(if (state.talking) R.string.orb_stop_talking else R.string.orb_start_talking)
    else -> stringResource(if (state.muted) R.string.orb_unmute else R.string.orb_mute)
}

/**
 * One fixed-height line under the orb: a notice when there is one ("Timer · 5 min"), the hint that a
 * tap interrupts while Parley speaks on the watch speaker, otherwise time, cost and network.
 */
@Composable
private fun Caption(state: ConversationUiState) {
    val meta = buildList {
        if (state.elapsedMs > 0) add(formatElapsed(state.elapsedMs))
        if (state.showCost && state.costUsd > 0) add(CostMeter.format(state.costUsd))
        if (state.transport != Transport.NONE && state.phase != Phase.CONNECTING) add(state.transport.label)
    }.joinToString(" · ")
    val hint = when {
        state.notice != null -> state.notice
        state.halfDuplex && state.phase == Phase.SPEAKING -> stringResource(R.string.notice_tap_to_interrupt)
        else -> null
    }
    Box(Modifier.height(17.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
        Crossfade(targetState = hint, label = "caption") { text ->
            if (text != null) {
                Text(
                    text,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(horizontal = 8.dp),
                )
            } else {
                Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

@Composable
private fun EmptyHint(state: ConversationUiState) {
    val text = when {
        state.phase == Phase.CONNECTING -> stringResource(R.string.hint_connecting, state.providerLabel)
        state.pushToTalk -> stringResource(R.string.hint_push_to_talk)
        state.phase == Phase.LISTENING -> stringResource(R.string.hint_ask_anything)
        else -> ""
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    )
}

/**
 * [latest]: part of the current exchange, shown brighter than what came before. [onSpokenLine]
 * receives where the line being spoken starts, for the answer being spoken.
 */
@Composable
private fun TurnLine(turn: UiTurn, latest: Boolean, onSpokenLine: ((Float) -> Unit)? = null) {
    when (turn.role) {
        TurnRole.USER -> Text(
            turn.text,
            style = MaterialTheme.typography.bodySmall,
            color = if (latest) MaterialTheme.colorScheme.onSurfaceVariant else ParleyColors.Faint,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        TurnRole.ASSISTANT -> {
            val spoken = turn.spokenChars.coerceIn(0, turn.text.length)
            var top by remember { mutableFloatStateOf(0f) }
            var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
            if (onSpokenLine != null) {
                layout?.let { l ->
                    val line = l.getLineForOffset((spoken - 1).coerceIn(0, (turn.text.length - 1).coerceAtLeast(0)))
                    onSpokenLine(top + l.getLineTop(line))
                }
            }
            Text(
                buildAnnotatedString {
                    append(turn.text.substring(0, spoken))
                    withStyle(SpanStyle(color = ParleyColors.Faint)) { append(turn.text.substring(spoken)) }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (latest) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                onTextLayout = { layout = it },
                modifier = Modifier.fillMaxWidth().onPlaced { top = it.positionInParent().y },
            )
        }
        TurnRole.NOTE, TurnRole.TOOL -> Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 10.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painterResource(if (turn.role == TurnRole.NOTE) R.drawable.ic_sticky_note_2 else R.drawable.ic_check),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(13.dp),
                )
                Spacer(Modifier.width(5.dp))
                Text(turn.text, style = MaterialTheme.typography.labelSmall, maxLines = 3)
            }
        }
    }
}

@Composable
private fun ErrorContent(state: ConversationUiState, onRetry: () -> Unit, onFixProvider: () -> Unit, onClose: () -> Unit) {
    val error = state.error ?: return
    val (title, text, fixable) = when (error.kind) {
        ErrorKind.AUTH -> Triple(stringResource(R.string.error_auth_title), stringResource(R.string.error_auth_text), true)
        ErrorKind.QUOTA -> Triple(stringResource(R.string.error_quota_title), stringResource(R.string.error_quota_text), false)
        ErrorKind.RATE_LIMIT -> Triple(stringResource(R.string.error_rate_title), stringResource(R.string.error_rate_text), false)
        ErrorKind.NETWORK -> Triple(stringResource(R.string.error_network_title), stringResource(R.string.error_network_text), false)
        ErrorKind.REJECTED -> Triple(stringResource(R.string.error_rejected_title), error.message, false)
        ErrorKind.SERVER -> Triple(stringResource(R.string.error_server_title), stringResource(R.string.error_server_text), false)
    }
    MessageScreen(
        icon = R.drawable.ic_error,
        iconTint = ParleyColors.Coral,
        title = title,
        text = text,
        actionLabel = stringResource(if (fixable) R.string.action_connect else R.string.action_try_again),
        actionIcon = if (fixable) R.drawable.ic_key else R.drawable.ic_refresh,
        onAction = if (fixable) onFixProvider else onRetry,
        extra = {
            Text(
                stringResource(R.string.action_close),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onClose).padding(8.dp),
            )
        },
    )
}

private const val FOLLOW_PAUSE_MS = 6_000L
private val FADE = 14.dp
/** How far above the spoken line the transcript keeps its top: one line of what was just said. */
private val LEAD = 18.dp

private fun formatElapsed(ms: Long): String {
    val seconds = ms / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
