package net.devemperor.parley.ui.components

import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.wear.compose.foundation.ScrollInfoProvider
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.input.RemoteInputIntentHelper
import androidx.wear.remote.interactions.RemoteActivityHelper
import io.nayuki.qrcodegen.QrCode

/** Keeps the display awake while [enabled] and this composable is on screen. */
@Composable
fun KeepScreenOn(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, enabled) {
        view.keepScreenOn = enabled
        onDispose { view.keepScreenOn = false }
    }
}

/** A non-scrolling screen with an edge button; the scaffold still needs a scroll source to place it. */
@Composable
fun EdgeButtonScaffold(
    edgeButton: @Composable BoxScope.() -> Unit,
    timeText: (@Composable () -> Unit)? = null,
    content: @Composable BoxScope.(PaddingValues) -> Unit,
) {
    val scroll = rememberScrollState()
    ScreenScaffold(
        scrollInfoProvider = remember(scroll) { ScrollInfoProvider(scroll) },
        edgeButton = edgeButton,
        timeText = timeText,
        content = content,
    )
}

/** A full screen with an icon, a title, some text and one action at the bottom edge. */
@Composable
fun MessageScreen(
    @DrawableRes icon: Int,
    title: String,
    text: String?,
    actionLabel: String,
    onAction: () -> Unit,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    @DrawableRes actionIcon: Int? = null,
    extra: (@Composable () -> Unit)? = null,
) {
    val scroll = rememberScrollState()
    ScreenScaffold(
        scrollInfoProvider = remember(scroll) { ScrollInfoProvider(scroll) },
        edgeButton = {
            EdgeButton(onClick = onAction) {
                actionIcon?.let { Icon(painterResource(it), contentDescription = null, modifier = Modifier.padding(end = 4.dp)) }
                Text(actionLabel)
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(padding).padding(horizontal = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(painterResource(icon), contentDescription = null, tint = iconTint, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(6.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            if (text != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            extra?.invoke()
        }
    }
}

/** A QR code on a white card (dark-on-light scans most reliably), sized by [modifier]. */
@Composable
fun QrCodeImage(text: String, modifier: Modifier = Modifier) {
    val qr = remember(text) { QrCode.encodeText(text, QrCode.Ecc.MEDIUM) }
    Box(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White)
            .padding(7.dp),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cell = size.minDimension / qr.size
            for (y in 0 until qr.size) {
                for (x in 0 until qr.size) {
                    if (qr.getModule(x, y)) {
                        drawRect(Color(0xFF050608), topLeft = Offset(x * cell, y * cell), size = Size(cell + 0.5f, cell + 0.5f))
                    }
                }
            }
        }
    }
}

/** Opens [url] in the browser of the paired phone. Returns false when no phone can be reached. */
fun openOnPhone(context: Context, url: String): Boolean = runCatching {
    RemoteActivityHelper(context).startRemoteActivity(
        Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE),
    )
    true
}.getOrDefault(false)

/**
 * Asks the user for text with the system input (keyboard, voice or the phone). [onResult] receives
 * null when they backed out.
 */
@Composable
fun rememberTextInput(label: String, onResult: (String?) -> Unit): () -> Unit {
    val launcher: ManagedActivityResultLauncher<Intent, ActivityResult> =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val text = result.data?.let { RemoteInput.getResultsFromIntent(it) }?.getCharSequence(TEXT_INPUT_KEY)?.toString()
            onResult(text?.trim()?.takeIf { it.isNotEmpty() })
        }
    return {
        val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
        RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(RemoteInput.Builder(TEXT_INPUT_KEY).setLabel(label).build()))
        runCatching { launcher.launch(intent) }.onFailure { onResult(null) }
    }
}

private const val TEXT_INPUT_KEY = "parley_text"

/** Section spacing used under titles on full-screen layouts. */
val ScreenPadding = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
