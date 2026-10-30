package net.devemperor.parley.ui.history

import android.content.Context
import android.net.Uri
import android.text.format.DateUtils
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OpenOnPhoneDialog
import androidx.wear.compose.material3.OpenOnPhoneDialogDefaults
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.curvedText
import net.devemperor.parley.Diagnostics
import net.devemperor.parley.R
import net.devemperor.parley.core.conversation.Conversation
import net.devemperor.parley.core.conversation.Role
import net.devemperor.parley.core.cost.CostMeter
import net.devemperor.parley.ui.components.CheckItem
import net.devemperor.parley.ui.components.ConfirmDialog
import net.devemperor.parley.ui.components.Header
import net.devemperor.parley.ui.components.Hint
import net.devemperor.parley.ui.components.ListScreen
import net.devemperor.parley.ui.components.MenuButton
import net.devemperor.parley.ui.components.openOnPhone
import net.devemperor.parley.ui.components.transformedContent
import net.devemperor.parley.ui.theme.ParleyColors

/**
 * The saved conversations, newest first. A long press starts choosing several: each tap then ticks a
 * conversation, and the buttons at the top send the ticked ones to the phone or delete them after
 * asking.
 */
@Composable
fun HistoryScreen(
    conversations: List<Conversation>,
    historyOff: Boolean,
    showCost: Boolean,
    onOpen: (String) -> Unit,
    onDelete: (Set<String>) -> Unit,
) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf(false) }
    var noPhone by remember { mutableStateOf(false) }
    // Conversations that disappeared (deleted, pruned) leave the selection too.
    val chosen = selected.intersect(conversations.map { it.id }.toSet())
    val choosing = chosen.isNotEmpty()
    BackHandler(enabled = choosing) { selected = emptySet() }
    fun toggle(id: String) {
        selected = if (id in chosen) chosen - id else chosen + id
    }
    ListScreen { spec ->
        item {
            Header(
                if (choosing) pluralStringResource(R.plurals.history_selected, chosen.size, chosen.size) else stringResource(R.string.history_title),
                spec,
            )
        }
        // At the top, not at the end of a long list where nobody would find them.
        if (choosing) {
            item {
                MenuButton(
                    stringResource(R.string.history_share), spec,
                    { sendToPhone(context, conversations.filter { it.id in chosen }) { ok -> if (ok) sent = true else noPhone = true } },
                    secondaryLabel = if (noPhone) stringResource(R.string.share_no_phone) else null,
                    icon = R.drawable.ic_send_to_mobile,
                )
            }
            item {
                MenuButton(
                    stringResource(R.string.history_delete_selected), spec, { confirmDelete = true },
                    icon = R.drawable.ic_delete, colors = ButtonDefaults.buttonColors(containerColor = ParleyColors.Coral, contentColor = Color.Black),
                )
            }
            item {
                MenuButton(stringResource(R.string.history_select_cancel), spec, { selected = emptySet() }, icon = R.drawable.ic_close)
            }
        }
        if (historyOff) item { Hint(stringResource(R.string.history_off), spec) }
        if (conversations.isEmpty() && !historyOff) item { Hint(stringResource(R.string.history_empty), spec) }
        conversations.forEach { conversation ->
            item(key = conversation.id) {
                val time = DateUtils.getRelativeTimeSpanString(
                    conversation.startedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE,
                ).toString()
                val title = conversation.title.ifBlank { stringResource(R.string.history_untitled) }
                val secondary = if (showCost) "$time · ${CostMeter.format(conversation.costUsd)}" else time
                if (choosing) {
                    CheckItem(title, conversation.id in chosen, { toggle(conversation.id) }, spec, secondary)
                } else {
                    MenuButton(
                        label = title,
                        secondaryLabel = secondary,
                        secondaryLines = 1,
                        spec = spec,
                        onClick = { onOpen(conversation.id) },
                        onLongClick = { toggle(conversation.id) },
                    )
                }
            }
        }
        if (conversations.size > 1 && !choosing) item { Hint(stringResource(R.string.history_select_hint), spec) }
    }
    ConfirmDialog(
        visible = confirmDelete,
        title = pluralStringResource(R.plurals.history_delete_selected_confirm, chosen.size.coerceAtLeast(1), chosen.size),
        onConfirm = {
            confirmDelete = false
            onDelete(chosen)
            selected = emptySet()
        },
        onDismiss = { confirmDelete = false },
    )
    OnPhoneDialog(sent) {
        sent = false
        selected = emptySet()
    }
}

/**
 * The transcript of one conversation: carrying it on comes first; sending it to the phone, reporting
 * and deleting at the end.
 */
@Composable
fun ConversationDetailScreen(conversation: Conversation, onContinue: () -> Unit, onReport: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf(false) }
    var noPhone by remember { mutableStateOf(false) }
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
        item {
            MenuButton(
                label = stringResource(R.string.history_continue),
                icon = R.drawable.ic_mic,
                highlighted = true,
                spec = spec,
                onClick = onContinue,
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
                label = stringResource(R.string.history_share),
                secondaryLabel = if (noPhone) stringResource(R.string.share_no_phone) else null,
                icon = R.drawable.ic_send_to_mobile,
                spec = spec,
                onClick = { sendToPhone(context, listOf(conversation)) { ok -> if (ok) sent = true else noPhone = true } },
            )
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
    ConfirmDialog(
        visible = confirmDelete,
        title = stringResource(R.string.history_delete_confirm),
        onConfirm = {
            confirmDelete = false
            onDelete()
        },
        onDismiss = { confirmDelete = false },
    )
    OnPhoneDialog(sent) { sent = false }
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
                        openOnPhone(context, uri) { ok -> if (ok) opened = true else failed = true }
                    },
                )
            }
        }
    }
    OnPhoneDialog(opened) {
        opened = false
        onSent()
    }
}

/** Wear's "continue on phone" animation, after something was opened there. */
@Composable
private fun OnPhoneDialog(visible: Boolean, onDone: () -> Unit) {
    val continueOnPhone = stringResource(R.string.report_continue)
    OpenOnPhoneDialog(
        visible = visible,
        onDismissRequest = onDone,
        curvedText = { curvedText(continueOnPhone) },
        content = { OpenOnPhoneDialogDefaults.Icon() },
    )
}

/**
 * Opens an e-mail with [conversations] on the paired phone, to send to oneself or to copy into a notes
 * app: a watch app can open a link on the phone, but cannot hand it a file or the share sheet.
 * [onResult] tells whether a phone took it.
 */
private fun sendToPhone(context: Context, conversations: List<Conversation>, onResult: (Boolean) -> Unit) {
    val subject = conversations.singleOrNull()?.title?.takeIf { it.isNotBlank() }?.let { "Parley: $it" } ?: "Parley"
    val body = transcriptText(context, conversations)
    if (Diagnostics.enabled) Log.d("ParleyEvent", "send to phone: ${body.length} characters\n$body")
    openOnPhone(context, "mailto:?subject=" + Uri.encode(subject) + "&body=" + Uri.encode(body), onResult)
}

/**
 * [conversations] as plain text, oldest first: date and time, then who said what. Cut after
 * [SHARE_LIMIT] characters, so the link stays small enough for its way to the phone.
 */
private fun transcriptText(context: Context, conversations: List<Conversation>): String {
    val you = context.getString(R.string.share_you)
    val parley = context.getString(R.string.app_name)
    val text = conversations.sortedBy { it.startedAt }.joinToString("\n\n") { conversation ->
        buildString {
            appendLine(
                DateUtils.formatDateTime(
                    context, conversation.startedAt,
                    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_SHOW_TIME,
                ),
            )
            conversation.turns.forEach { turn ->
                appendLine(
                    when (turn.role) {
                        Role.USER -> "$you: ${turn.text}"
                        Role.ASSISTANT -> "$parley: ${turn.text}"
                        Role.NOTE -> "✎ ${turn.text}"
                        Role.TOOL -> "✓ ${turn.text}"
                    },
                )
            }
        }.trimEnd()
    }
    return if (text.length > SHARE_LIMIT) text.take(SHARE_LIMIT) + "\n…" else text
}

private const val SHARE_LIMIT = 20_000

const val REPORT_ADDRESS = "contact@devemperor.net"
