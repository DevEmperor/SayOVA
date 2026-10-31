package net.devemperor.talova.ui.components

import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.util.Base64
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.modifiers.TextAutoSizeLayoutScope
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
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.wear.compose.foundation.ScrollInfoProvider
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.LocalContentColor
import androidx.wear.compose.material3.LocalTextStyle
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.input.RemoteInputIntentHelper
import androidx.wear.remote.interactions.RemoteActivityHelper
import io.nayuki.qrcodegen.QrCode
import net.devemperor.talova.BuildConfig

/** Keeps the display awake while [enabled] and this composable is on screen. */
@Composable
fun KeepScreenOn(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, enabled) {
        view.keepScreenOn = enabled
        onDispose { view.keepScreenOn = false }
    }
}

/**
 * A screen that does not scroll, with an edge button. The scaffold sizes its edge button from the
 * room the content leaves below its last item, and a plain ScrollState always reports none, which
 * keeps the button at height zero. A static screen has all the room there is.
 */
@Composable
fun EdgeButtonScaffold(
    edgeButton: @Composable BoxScope.() -> Unit,
    timeText: (@Composable () -> Unit)? = null,
    content: @Composable BoxScope.(PaddingValues) -> Unit,
) {
    val screenHeight = LocalWindowInfo.current.containerSize.height.toFloat()
    val info = remember(screenHeight) { StaticScrollInfo(screenHeight) }
    ScreenScaffold(
        scrollInfoProvider = info,
        edgeButton = edgeButton,
        timeText = timeText,
        content = content,
    )
}

private class StaticScrollInfo(override val lastItemOffset: Float) : ScrollInfoProvider {
    override val isScrollAwayValid = false
    override val isScrollable = false
    override val isScrollInProgress = false
    override val anchorItemOffset = 0f
}

/**
 * A full screen with an icon, a title, some text and one action at the bottom edge. It is a list,
 * so a long text scrolls on a small watch instead of being cut off; short content sits centred.
 */
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
    val listState = rememberTransformingLazyColumnState()
    ScreenScaffold(
        scrollState = listState,
        edgeButton = {
            EdgeButton(onClick = onAction) {
                actionIcon?.let { Icon(painterResource(it), contentDescription = null, modifier = Modifier.padding(end = 4.dp).size(20.dp)) }
                FitText(actionLabel)
            }
        },
    ) { padding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        ) {
            item { Icon(painterResource(icon), contentDescription = null, tint = iconTint, modifier = Modifier.size(26.dp)) }
            item {
                Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 14.dp))
            }
            if (text != null) {
                item {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 18.dp),
                    )
                }
            }
            if (extra != null) {
                item { Column(horizontalAlignment = Alignment.CenterHorizontally) { extra() } }
            }
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

/**
 * Opens [url] on the paired phone; [onResult] tells, on the main thread, whether the phone took it.
 * Without a phone the request fails only after a moment: taken as sent at once, the watch showed
 * "Continue on phone" with no phone at all.
 */
fun openOnPhone(context: Context, url: String, onResult: (Boolean) -> Unit) {
    val sent = runCatching {
        RemoteActivityHelper(context).startRemoteActivity(
            Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE),
        )
    }.getOrElse {
        onResult(false)
        return
    }
    sent.addListener({ onResult(runCatching { sent.get() }.isSuccess) }, ContextCompat.getMainExecutor(context))
}

/**
 * The share page next to the pairing page (pair/public/share.html) with [text], Share (any app) and
 * Copy: a watch app can only open a link on the phone, not its share sheet. The text travels in the
 * link's fragment, which the phone's browser never sends to the server.
 */
fun shareLink(text: String): String = sharePage("t", text)

/** The share page with a calendar event (see PhoneLinks.event), which it hands the phone's calendar app as a file. */
fun eventLink(event: String): String = sharePage("e", event)

private fun sharePage(key: String, value: String): String =
    "${BuildConfig.PAIR_BASE_URL}/share#$key=" + Base64.encodeToString(value.toByteArray(), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

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

private const val TEXT_INPUT_KEY = "talova_text"

/**
 * One line of text that shrinks, down to [minScale] of its size, before it is cut off: translations
 * run up to half again as long as the English, and an edge button or the line under the orb has no
 * room for a second line. Material 3's Text on Wear cannot size itself, BasicText can.
 */
@Composable
fun FitText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    textAlign: TextAlign? = null,
    minScale: Float = 0.7f,
) {
    val size = style.fontSize.takeIf { it.isSp } ?: 14.sp
    BasicText(
        text,
        modifier = modifier,
        style = style.merge(color = color.takeOrElse { style.color.takeOrElse { LocalContentColor.current } }, textAlign = textAlign ?: TextAlign.Unspecified),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        autoSize = TextAutoSize.StepBased(minFontSize = size * minScale, maxFontSize = size, stepSize = 0.5.sp),
    )
}

/**
 * Text over a few lines that keeps its words whole. Next to an icon and a switch the line is narrow,
 * and a long word ("Sprechtaste") was hyphenated; now the text gets smaller, down to [minScale], until
 * every line ends between words. A word too wide even then ("Benachrichtigungen" would need 60 %) is
 * hyphenated at that smaller size, in fewer pieces than before.
 */
@Composable
fun WordFitText(
    text: String,
    maxLines: Int,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    minScale: Float = 0.75f,
) {
    val size = style.fontSize.takeIf { it.isSp } ?: 14.sp
    // The line height shrinks with the text, so smaller lines do not drift apart.
    val lineHeight = style.lineHeight.takeIf { it.isSp }?.let { (it.value / size.value).em } ?: style.lineHeight
    BasicText(
        text,
        modifier = modifier,
        // The theme's hyphenation stays on: it only splits a word that does not fit its line, which
        // the sizing below avoids where it can, and it marks the split where it cannot.
        style = style.merge(
            color = color.takeOrElse { style.color.takeOrElse { LocalContentColor.current } },
            lineHeight = lineHeight,
        ),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        autoSize = WholeWords(size * minScale, size),
    )
}

/** The largest size, in half-sp steps, at which the text fits and every line ends between words. */
private data class WholeWords(val min: TextUnit, val max: TextUnit) : TextAutoSize {
    override fun TextAutoSizeLayoutScope.getFontSize(constraints: Constraints, text: AnnotatedString): TextUnit {
        var size = max.value
        while (size > min.value) {
            val layout = performLayout(constraints, text, size.sp)
            if (!layout.hasVisualOverflow && breaksBetweenWords(layout, text.text)) return size.sp
            size -= 0.5f
        }
        return min
    }

    private fun breaksBetweenWords(layout: TextLayoutResult, text: String): Boolean = (0 until layout.lineCount - 1).all { line ->
        val end = layout.getLineEnd(line)
        end !in 1 until text.length || !text[end - 1].isLetterOrDigit() || !text[end].isLetterOrDigit() || breaksAnywhere(text[end])
    }

    /** Chinese, Japanese and Korean lines may end between any two characters; that is no split word. */
    private fun breaksAnywhere(char: Char): Boolean = Character.UnicodeScript.of(char.code) in WIDE_SCRIPTS

    private companion object {
        val WIDE_SCRIPTS = setOf(
            Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL,
        )
    }
}

/** The watch's screen width, to size the orb and the QR code for small and large watches alike. */
@Composable
fun screenWidth(): Dp = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }

/** Section spacing used under titles on full-screen layouts. */
val ScreenPadding = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
