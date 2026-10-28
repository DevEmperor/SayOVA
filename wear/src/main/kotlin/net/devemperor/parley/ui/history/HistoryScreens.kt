package net.devemperor.parley.ui.history

import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OpenOnPhoneDialog
import androidx.wear.compose.material3.OpenOnPhoneDialogDefaults
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.curvedText
import net.devemperor.parley.R
import net.devemperor.parley.core.conversation.Conversation
import net.devemperor.parley.core.conversation.Role
import net.devemperor.parley.core.cost.CostMeter
import net.devemperor.parley.ui.components.Header
import net.devemperor.parley.ui.components.Hint
import net.devemperor.parley.ui.components.ListScreen
import net.devemperor.parley.ui.components.MenuButton
import net.devemperor.parley.ui.components.openOnPhone
import net.devemperor.parley.ui.components.transformedContent

@Composable
fun HistoryScreen(conversations: List<Conversation>, historyOff: Boolean, showCost: Boolean, onOpen: (String) -> Unit) {
    ListScreen { spec ->
        item { Header(stringResource(R.string.history_title), spec) }
        if (historyOff) item { Hint(stringResource(R.string.history_off), spec) }
        if (conversations.isEmpty() && !historyOff) item { Hint(stringResource(R.string.history_empty), spec) }
        conversations.forEach { conversation ->
            item(key = conversation.id) {
                val time = DateUtils.getRelativeTimeSpanString(
                    conversation.startedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE,
                ).toString()
                MenuButton(
                    label = conversation.title.ifBlank { stringResource(R.string.history_untitled) },
                    secondaryLabel = if (showCost) "$time · ${CostMeter.format(conversation.costUsd)}" else time,
                    secondaryLines = 1,
                    spec = spec,
                    onClick = { onOpen(conversation.id) },
                )
            }
        }
    }
}

/** The transcript of one conversation, with reporting and deleting at the end. */
@Composable
fun ConversationDetailScreen(conversation: Conversation, onReport: () -> Unit, onDelete: () -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    ListScreen { spec ->
        item {
            Header(
                DateUtils.formatDateTime(
                    LocalContext.current, conversation.startedAt,
                    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
                ),
                spec,
            )
        }
        conversation.turns.forEachIndexed { index, turn ->
            item(key = "t$index") {
                val (style, color, prefix) = when (turn.role) {
                    Role.USER -> Triple(MaterialTheme.typography.bodySmall, MaterialTheme.colorScheme.onSurfaceVariant, "")
                    Role.ASSISTANT -> Triple(MaterialTheme.typography.bodyMedium, MaterialTheme.colorScheme.onSurface, "")
                    Role.NOTE -> Triple(MaterialTheme.typography.labelMedium, MaterialTheme.colorScheme.secondary, "✎ ")
                    Role.TOOL -> Triple(MaterialTheme.typography.labelSmall, MaterialTheme.colorScheme.primary, "✓ ")
                }
                Text(
                    prefix + turn.text,
                    style = style,
                    color = color,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp).transformedContent(this, spec),
                )
            }
        }
        item {
            MenuButton(
                label = stringResource(R.string.history_report),
                icon = R.drawable.ic_flag,
                spec = spec,
                onClick = onReport,
            )
        }
        item {
            MenuButton(
                label = stringResource(R.string.history_delete),
                icon = R.drawable.ic_delete,
                spec = spec,
                onClick = { confirmDelete = true },
            )
        }
    }
    AlertDialog(
        visible = confirmDelete,
        onDismissRequest = { confirmDelete = false },
        icon = { Icon(painterResource(R.drawable.ic_delete), contentDescription = null) },
        title = { Text(stringResource(R.string.history_delete_confirm)) },
        confirmButton = {
            AlertDialogDefaults.ConfirmButton(onClick = {
                confirmDelete = false
                onDelete()
            })
        },
    )
}

/**
 * Google Play asks apps that generate content with AI to let users report offensive output. The
 * report is an e-mail the user sends from the phone, with the answer they chose; nothing is sent
 * from the watch behind their back.
 */
@Composable
fun ReportScreen(conversation: Conversation, onSent: () -> Unit) {
    val context = LocalContext.current
    var opened by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val answers = conversation.turns.filter { it.role == Role.ASSISTANT }
    val continueOnPhone = stringResource(R.string.report_continue)
    ListScreen { spec ->
        item { Header(stringResource(R.string.report_title), spec) }
        item { Hint(stringResource(if (failed) R.string.report_no_phone else R.string.report_hint), spec) }
        answers.forEachIndexed { index, turn ->
            item(key = "a$index") {
                MenuButton(
                    label = turn.text,
                    spec = spec,
                    onClick = {
                        val body = "Model: ${conversation.provider} / ${conversation.model}\n\nResponse:\n${turn.text}\n\nWhat is wrong with it?\n"
                        val uri = "mailto:$REPORT_ADDRESS?subject=" + Uri.encode("Parley: reported response") + "&body=" + Uri.encode(body)
                        if (openOnPhone(context, uri)) opened = true else failed = true
                    },
                )
            }
        }
    }
    OpenOnPhoneDialog(
        visible = opened,
        onDismissRequest = {
            opened = false
            onSent()
        },
        curvedText = { curvedText(continueOnPhone) },
        content = { OpenOnPhoneDialogDefaults.Icon() },
    )
}

const val REPORT_ADDRESS = "contact@devemperor.net"
