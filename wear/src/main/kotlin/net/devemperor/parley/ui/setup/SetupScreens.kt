package net.devemperor.parley.ui.setup

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OpenOnPhoneDialog
import androidx.wear.compose.material3.OpenOnPhoneDialogDefaults
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import androidx.wear.compose.material3.curvedText
import net.devemperor.parley.R
import net.devemperor.parley.core.provider.ProviderCatalog
import net.devemperor.parley.core.provider.ProviderId
import net.devemperor.parley.ui.components.CheckButton
import net.devemperor.parley.ui.components.CrossButton
import net.devemperor.parley.ui.components.EdgeButtonScaffold
import net.devemperor.parley.ui.components.FitText
import net.devemperor.parley.ui.components.Header
import net.devemperor.parley.ui.components.Hint
import net.devemperor.parley.ui.components.ListScreen
import net.devemperor.parley.ui.components.MenuButton
import net.devemperor.parley.ui.components.MessageScreen
import net.devemperor.parley.ui.components.Orb
import net.devemperor.parley.ui.components.OrbHandoff
import net.devemperor.parley.ui.components.QrCodeImage
import net.devemperor.parley.ui.components.SwitchItem
import net.devemperor.parley.ui.components.openOnPhone
import net.devemperor.parley.ui.components.screenWidth
import net.devemperor.parley.ui.theme.ParleyColors

/**
 * The first page of the introduction: Parley, one line on what it is, the way into the setup, and the
 * sample conversation for anyone who wants to hear Parley first. The sample grows out of this orb and
 * shrinks back onto it, like a conversation on the home screen (see OrbHandoff).
 */
@Composable
fun WelcomeScreen(onStart: () -> Unit, onSample: () -> Unit) {
    EdgeButtonScaffold(
        edgeButton = {
            EdgeButton(onClick = onStart, buttonSize = EdgeButtonSize.ExtraSmall) { FitText(stringResource(R.string.welcome_start)) }
        },
    ) { padding ->
        // The scaffold keeps room for a larger edge button than this extra-small one; the sample's
        // button needs that room.
        Column(
            Modifier.fillMaxSize().padding(top = padding.calculateTopPadding(), bottom = screenWidth() * 0.25f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.size(screenWidth() * 0.22f).onGloballyPositioned { OrbHandoff.homeBounds = it.boundsInWindow() }) {
                Orb(phase = null, level = 0f, modifier = Modifier.fillMaxSize())
            }
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.welcome_tagline),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(8.dp))
            // A small pill as wide as its words: the edge button stays the one main action.
            Row(
                Modifier
                    .widthIn(max = screenWidth() * 0.78f)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .clickable(role = Role.Button, onClick = onSample)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painterResource(R.drawable.ic_volume_up),
                    contentDescription = null,
                    tint = ParleyColors.TealBright,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                // Smaller rather than cut off where the words run long.
                FitText(stringResource(R.string.welcome_sample), style = MaterialTheme.typography.labelMedium, minScale = 0.6f)
            }
        }
    }
}

/**
 * The introduction's tip for starting faster. The watch's own button settings cannot be opened at the
 * right page from outside, so the way there is described and the watch settings open at their start;
 * "Talk when opened" is switched right here.
 */
@Composable
fun QuickStartScreen(startOnLaunch: Boolean, onStartOnLaunch: (Boolean) -> Unit, onContinue: () -> Unit) {
    val context = LocalContext.current
    val talkWhenOpened = stringResource(R.string.settings_start_on_launch)
    ListScreen { spec ->
        item { Header(stringResource(R.string.quick_title), spec) }
        item { Hint(stringResource(R.string.quick_text, talkWhenOpened), spec) }
        item {
            SwitchItem(
                label = talkWhenOpened,
                checked = startOnLaunch,
                onCheckedChange = onStartOnLaunch,
                spec = spec,
                icon = R.drawable.ic_mic,
            )
        }
        item {
            MenuButton(
                label = stringResource(R.string.quick_watch_settings),
                icon = R.drawable.ic_watch,
                spec = spec,
                onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) } },
            )
        }
        item { Hint(stringResource(R.string.quick_more), spec) }
        item {
            MenuButton(
                label = stringResource(R.string.action_continue),
                icon = R.drawable.ic_arrow_forward,
                spec = spec,
                highlighted = true,
                onClick = onContinue,
            )
        }
    }
}

fun Context.hasMicrophone(): Boolean =
    checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

/** Explains why the microphone is needed, then asks; leads to the app settings after a hard refusal. */
@Composable
fun PermissionScreen(onGranted: () -> Unit) {
    val context = LocalContext.current
    var denials by rememberSaveable { mutableIntStateOf(0) }
    // Notifications ride along: without them the ongoing-conversation chip on the watch face is hidden.
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results[Manifest.permission.RECORD_AUDIO] == true) onGranted() else denials++
    }
    val permissions = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
    LifecycleResumeEffect(Unit) {
        if (context.hasMicrophone()) onGranted()
        onPauseOrDispose { }
    }
    val blocked = denials >= 2
    MessageScreen(
        icon = R.drawable.ic_mic,
        title = stringResource(R.string.permission_title),
        text = stringResource(if (blocked) R.string.permission_blocked else R.string.permission_text),
        actionLabel = stringResource(if (blocked) R.string.permission_settings else R.string.permission_allow),
        onAction = {
            if (blocked) {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                )
            } else {
                launcher.launch(permissions)
            }
        },
    )
}

@Composable
fun ProviderChoiceScreen(connected: Set<ProviderId>, onChoose: (ProviderId) -> Unit) {
    ListScreen { spec ->
        item { Header(stringResource(R.string.provider_choose), spec) }
        item { Hint(stringResource(R.string.provider_choose_hint), spec) }
        item {
            MenuButton(
                label = ProviderCatalog.gemini.displayName,
                secondaryLabel = if (ProviderId.GEMINI in connected) stringResource(R.string.provider_connected) else stringResource(R.string.provider_gemini_note),
                icon = R.drawable.ic_bolt,
                spec = spec,
                highlighted = true,
                onClick = { onChoose(ProviderId.GEMINI) },
            )
        }
        item {
            MenuButton(
                label = ProviderCatalog.openAi.displayName,
                secondaryLabel = if (ProviderId.OPENAI in connected) stringResource(R.string.provider_connected) else stringResource(R.string.provider_openai_note),
                icon = R.drawable.ic_record_voice_over,
                spec = spec,
                onClick = { onChoose(ProviderId.OPENAI) },
            )
        }
    }
}

/**
 * Shows the QR code for the pairing page and walks through the handover. Everything after the code
 * arrives (compare, check, done) happens on this one screen.
 */
@Composable
fun PairingScreen(viewModel: PairingViewModel, provider: ProviderId, doneLabel: String, onDone: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(provider) { viewModel.start(provider) }

    when (val s = state) {
        PairingState.Preparing -> Busy(stringResource(R.string.pair_preparing))
        is PairingState.Waiting -> QrContent(s)
        is PairingState.Received -> CompareCode(
            code = s.code,
            onMatch = viewModel::confirm,
            onMismatch = { viewModel.start(provider) },
        )
        is PairingState.Checking -> Busy(stringResource(R.string.pair_checking, PairingViewModel.providerName(s.provider)))
        is PairingState.Connected -> MessageScreen(
            icon = R.drawable.ic_check,
            title = stringResource(R.string.pair_connected_title),
            text = stringResource(
                if (s.verified) R.string.pair_connected_text else R.string.pair_connected_unverified,
                PairingViewModel.providerName(s.provider),
            ),
            actionLabel = doneLabel,
            onAction = onDone,
        )
        is PairingState.Failed -> MessageScreen(
            icon = R.drawable.ic_error,
            iconTint = ParleyColors.Coral,
            title = stringResource(
                when (s.reason) {
                    PairingState.Reason.EXPIRED -> R.string.pair_failed_expired
                    PairingState.Reason.REJECTED_KEY -> R.string.pair_failed_rejected
                    PairingState.Reason.NO_CONNECTION -> R.string.pair_failed_offline
                    PairingState.Reason.BROKEN -> R.string.pair_failed_broken
                },
            ),
            text = s.detail,
            actionLabel = stringResource(R.string.action_try_again),
            onAction = { viewModel.start(provider) },
        )
    }
}

@Composable
private fun QrContent(state: PairingState.Waiting) {
    val context = LocalContext.current
    var phoneOpened by remember { mutableStateOf(false) }
    val continueOnPhone = stringResource(R.string.pair_continue_on_phone)
    // The instruction runs along the top bezel where the clock usually is: the QR code needs the
    // middle of the screen, and a straight line of text there collided with the clock.
    val instruction = stringResource(if (state.offline) R.string.pair_waiting_offline else R.string.pair_scan)
    val instructionColor = if (state.offline) ParleyColors.Coral else MaterialTheme.colorScheme.onSurfaceVariant
    EdgeButtonScaffold(
        // Wide enough for the longest translations; the QR code sits low enough to leave the sides free.
        timeText = { TimeText(maxSweepAngle = 170f) { curvedText(instruction, color = instructionColor, maxSweepAngle = 170f) } },
        edgeButton = {
            EdgeButton(
                onClick = { openOnPhone(context, state.link) { phoneOpened = it } },
                buttonSize = EdgeButtonSize.ExtraSmall,
                colors = ButtonDefaults.filledTonalButtonColors(),
            ) {
                FitText(stringResource(R.string.pair_open_on_phone))
            }
        },
    ) { _ ->
        // As large as the round screen allows between the curved instruction and the button, sitting
        // on the button so its top corners stay clear of the text: a phone camera at arm's length
        // reads it at once. Typing a 40-character key on a watch was never a real alternative, so
        // the code has the screen to itself.
        // The scaffold's bottom padding leaves room for a larger button than this extra-small one.
        Box(
            Modifier.fillMaxSize().padding(bottom = screenWidth() * 0.265f),
            contentAlignment = Alignment.BottomCenter,
        ) {
            QrCodeImage(state.link, Modifier.size(screenWidth() * 0.5f))
        }
    }
    OpenOnPhoneDialog(
        visible = phoneOpened,
        onDismissRequest = { phoneOpened = false },
        curvedText = { curvedText(continueOnPhone) },
        content = { OpenOnPhoneDialogDefaults.Icon() },
    )
}

/**
 * The phone shows a six-digit code when it sends the key; the watch derives the same code from what
 * arrived. Equal codes prove the key came from the user's own phone and nobody in between, so this is
 * one glance and one tap: the code fills the middle, below it the two round buttons of every yes-or-no
 * question in Parley, the highlighted check ("Matches") and the muted cross ("Doesn't match"), which
 * starts over with a new QR code.
 */
@Composable
private fun CompareCode(code: String, onMatch: () -> Unit, onMismatch: () -> Unit) {
    val question = stringResource(R.string.pair_compare_title)
    val questionColor = MaterialTheme.colorScheme.onSurfaceVariant
    ScreenScaffold(
        timeText = { TimeText(maxSweepAngle = 170f) { curvedText(question, color = questionColor, maxSweepAngle = 170f) } },
    ) { _ ->
        val width = screenWidth()
        Column(
            Modifier.fillMaxSize().padding(top = width * 0.08f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                code.chunked(3).joinToString(" "),
                fontFamily = FontFamily.Monospace,
                fontSize = 32.sp,
                color = ParleyColors.TealBright,
            )
            Spacer(Modifier.height(width * 0.07f))
            // Cross left, check right, as in the dialogs.
            Row(
                horizontalArrangement = Arrangement.spacedBy(width * 0.08f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CrossButton(onMismatch, stringResource(R.string.pair_compare_mismatch))
                CheckButton(onMatch, stringResource(R.string.pair_compare_match))
            }
        }
    }
}

@Composable
private fun Busy(text: String) {
    ScreenScaffold { _ ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Orb(phase = net.devemperor.parley.session.Phase.THINKING, level = 0f, modifier = Modifier.size(56.dp))
                Spacer(Modifier.height(4.dp))
                Text(text, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 28.dp))
            }
        }
    }
}

/** A tonal button for secondary actions on full screens. */
@Composable
fun SecondaryAction(label: String, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = Modifier.fillMaxWidth(0.8f)) { Text(label) }
}
