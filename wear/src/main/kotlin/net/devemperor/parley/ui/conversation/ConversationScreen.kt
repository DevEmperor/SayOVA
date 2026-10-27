package net.devemperor.parley.ui.conversation

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
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
import net.devemperor.parley.session.ConversationUiState
import net.devemperor.parley.session.Phase
import net.devemperor.parley.session.UiTurn
import net.devemperor.parley.ui.components.KeepScreenOn
import net.devemperor.parley.ui.components.MessageScreen
import net.devemperor.parley.ui.components.Orb
import net.devemperor.parley.ui.theme.ParleyColors

/**
 * The live conversation: status on the bezel, the orb as the one control, and the transcript
 * scrolling under it. While Parley speaks, the heard part of the answer is bright and the rest dim.
 */
@Composable
fun ConversationScreen(
    state: ConversationUiState,
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

    val listState = rememberTransformingLazyColumnState()
    val turns = state.turns
    // Follow the newest line, unless the user scrolled back with the crown or a swipe in the last
    // few seconds; our own scrolling must not count as theirs.
    var scrollingOurselves by remember { mutableStateOf(false) }
    var userScrolledAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress && !scrollingOurselves) userScrolledAt = SystemClock.elapsedRealtime()
    }
    LaunchedEffect(turns.size, turns.lastOrNull()?.text?.length) {
        if (turns.isNotEmpty() && SystemClock.elapsedRealtime() - userScrolledAt > FOLLOW_PAUSE_MS) {
            scrollingOurselves = true
            try {
                listState.animateScrollToItem(turns.size)
            } finally {
                scrollingOurselves = false
            }
        }
    }

    val statusColor = ParleyColors.forPhase(state.phase)
    val status = statusText(state)
    ScreenScaffold(
        scrollState = listState,
        timeText = {
            TimeText { time ->
                timeTextCurvedText(time)
                timeTextSeparator()
                curvedText(status, color = statusColor)
            }
        },
        edgeButton = {
            EdgeButton(
                onClick = onEnd,
                buttonSize = EdgeButtonSize.ExtraSmall,
                colors = ButtonDefaults.filledTonalButtonColors(),
                enabled = state.active,
            ) {
                Icon(painterResource(R.drawable.ic_call_end), contentDescription = stringResource(R.string.action_end), modifier = Modifier.size(20.dp))
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            TransformingLazyColumn(
                state = listState,
                contentPadding = PaddingValues(top = 104.dp, bottom = padding.calculateBottomPadding() + 8.dp, start = 14.dp, end = 14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item(key = "top") { Spacer(Modifier.height(1.dp)) }
                if (turns.isEmpty()) {
                    item(key = "hint") { EmptyHint(state) }
                }
                items(turns.size, key = { turns[it].key + turns[it].role }) { index -> TurnLine(turns[index]) }
            }

            // The orb and its caption float above the transcript, which fades out under them.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(118.dp)
                    .background(Brush.verticalGradient(0.7f to Color.Black, 1f to Color.Transparent)),
            )
            Column(
                Modifier.fillMaxWidth().padding(top = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val orbLabel = orbDescription(state)
                Box(
                    Modifier
                        .size(62.dp)
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
                Caption(state)
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

@Composable
private fun Caption(state: ConversationUiState) {
    val parts = buildList {
        if (state.elapsedMs > 0) add(formatElapsed(state.elapsedMs))
        if (state.showCost && state.costUsd > 0) add(CostMeter.format(state.costUsd))
        if (state.transport.label.isNotEmpty() && state.phase != Phase.CONNECTING) add(state.transport.label)
    }
    Spacer(Modifier.height(2.dp))
    AnimatedVisibility(visible = state.notice != null || state.halfDuplex, enter = fadeIn(), exit = fadeOut()) {
        val notice = state.notice ?: stringResource(R.string.notice_tap_to_interrupt)
        Text(
            notice,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .padding(horizontal = 8.dp, vertical = 1.dp),
        )
    }
    if (state.notice == null && !state.halfDuplex && parts.isNotEmpty()) {
        Text(
            parts.joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
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
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    )
}

@Composable
private fun TurnLine(turn: UiTurn) {
    when (turn.role) {
        TurnRole.USER -> Text(
            turn.text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        TurnRole.ASSISTANT -> {
            val spoken = turn.spokenChars.coerceIn(0, turn.text.length)
            Text(
                buildAnnotatedString {
                    append(turn.text.substring(0, spoken))
                    withStyle(SpanStyle(color = ParleyColors.Faint)) { append(turn.text.substring(spoken)) }
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
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
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painterResource(if (turn.role == TurnRole.NOTE) R.drawable.ic_sticky_note_2 else R.drawable.ic_check),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
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
            Spacer(Modifier.height(4.dp))
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

private fun formatElapsed(ms: Long): String {
    val seconds = ms / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
