package net.devemperor.sayova.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonColors
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CheckboxButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListHeaderDefaults
import androidx.wear.compose.material3.ListSubHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.RadioButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextDefaults
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight

/**
 * A scrolling Wear list with the Material 3 scale-and-fade transformation at the edges.
 *
 * Every item below declares the minimum list padding Material 3 gives its type
 * (minimumVerticalContentPadding); the list pads its first and last item by it, so on a round watch
 * they can always be scrolled fully inside the display. Without it Google Play's review found
 * "Continue" at the end of the introduction's quick-start page and OpenAI at the end of the provider
 * choice still under the curved bottom edge (1.0.0, small round watch).
 */
@Composable
fun ListScreen(
    edgeButton: (@Composable BoxScope.() -> Unit)? = null,
    content: TransformingLazyColumnScope.(TransformationSpec) -> Unit,
) {
    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    if (edgeButton != null) {
        ScreenScaffold(scrollState = listState, edgeButton = edgeButton) { contentPadding ->
            TransformingLazyColumn(state = listState, contentPadding = contentPadding) { content(spec) }
        }
    } else {
        ScreenScaffold(scrollState = listState) { contentPadding ->
            TransformingLazyColumn(state = listState, contentPadding = contentPadding) { content(spec) }
        }
    }
}

@Composable
fun TransformingLazyColumnItemScope.Header(text: String, spec: TransformationSpec) {
    ListHeader(
        modifier = Modifier
            .fillMaxWidth()
            .transformedHeight(this, spec)
            .minimumVerticalContentPadding(
                top = ListHeaderDefaults.minimumTopListContentPadding,
                bottom = ListHeaderDefaults.minimumBottomListContentPadding,
            ),
        transformation = SurfaceTransformation(spec),
    ) {
        Text(text, textAlign = TextAlign.Center)
    }
}

@Composable
fun TransformingLazyColumnItemScope.SubHeader(text: String, spec: TransformationSpec) {
    ListSubHeader(
        modifier = Modifier
            .fillMaxWidth()
            .transformedHeight(this, spec)
            .minimumVerticalContentPadding(
                top = ListHeaderDefaults.minimumTopListContentPadding,
                bottom = ListHeaderDefaults.minimumBottomListContentPadding,
            ),
        transformation = SurfaceTransformation(spec),
    ) {
        Text(text)
    }
}

/**
 * For list items that are no Material surface (text, a column of text): shrinks at the list's edges
 * like the buttons do, and draws shrunk too. transformedHeight alone only reports the smaller height;
 * the text inside kept its full size and slid over its neighbours while scrolling. Such items also
 * declare TextDefaults' list padding (see [ListScreen]).
 */
fun Modifier.transformedContent(scope: TransformingLazyColumnItemScope, spec: TransformationSpec): Modifier =
    transformedHeight(scope, spec).graphicsLayer {
        with(scope) { with(spec) { applyContainerTransformation(scrollProgress) } }
    }

/** Explanatory text inside a list, centred and muted. */
@Composable
fun TransformingLazyColumnItemScope.Hint(text: String, spec: TransformationSpec) {
    Text(
        text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .transformedContent(this, spec)
            .minimumVerticalContentPadding(
                top = TextDefaults.minimumTopListContentPadding,
                bottom = TextDefaults.minimumBottomListContentPadding,
            ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
fun TransformingLazyColumnItemScope.MenuButton(
    label: String,
    spec: TransformationSpec,
    onClick: () -> Unit,
    secondaryLabel: String? = null,
    @DrawableRes icon: Int? = null,
    highlighted: Boolean = false,
    // Four: at the largest font on a small watch Gemini's "Recommended · about 1–2 ¢ a minute" needs them.
    secondaryLines: Int = 4,
    onLongClick: (() -> Unit)? = null,
    colors: ButtonColors? = null,
) {
    Button(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = Modifier
            .fillMaxWidth()
            .transformedHeight(this, spec)
            .minimumVerticalContentPadding(ButtonDefaults.minimumVerticalListContentPadding),
        transformation = SurfaceTransformation(spec),
        colors = colors ?: if (highlighted) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
        icon = icon?.let { res -> { Icon(painterResource(res), contentDescription = null) } },
        secondaryLabel = secondaryLabel?.let { text -> { Text(text, maxLines = secondaryLines, overflow = TextOverflow.Ellipsis) } },
        // Three lines and a long word shrinking: "In den Uhreinstellungen erlauben" was cut off at two.
        label = { WordFitText(label, maxLines = 3) },
    )
}

@Composable
fun TransformingLazyColumnItemScope.SwitchItem(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    spec: TransformationSpec,
    secondaryLabel: String? = null,
    @DrawableRes icon: Int? = null,
    enabled: Boolean = true,
) {
    SwitchButton(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .transformedHeight(this, spec)
            .minimumVerticalContentPadding(ButtonDefaults.minimumVerticalListContentPadding),
        transformation = SurfaceTransformation(spec),
        icon = icon?.let { res -> { Icon(painterResource(res), contentDescription = null) } },
        // The switch leaves the text a narrow column: four lines for longer translations, and a label
        // that shrinks rather than splitting a long word.
        secondaryLabel = secondaryLabel?.let { text -> { Text(text, maxLines = 4, overflow = TextOverflow.Ellipsis) } },
        label = { WordFitText(label, maxLines = 3) },
    )
}

@Composable
fun TransformingLazyColumnItemScope.CheckItem(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    spec: TransformationSpec,
    secondaryLabel: String? = null,
) {
    CheckboxButton(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = Modifier
            .fillMaxWidth()
            .transformedHeight(this, spec)
            .minimumVerticalContentPadding(ButtonDefaults.minimumVerticalListContentPadding),
        transformation = SurfaceTransformation(spec),
        secondaryLabel = secondaryLabel?.let { text -> { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        label = { Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
    )
}

@Composable
fun TransformingLazyColumnItemScope.RadioItem(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    spec: TransformationSpec,
    secondaryLabel: String? = null,
    @DrawableRes icon: Int? = null,
) {
    RadioButton(
        selected = selected,
        onSelect = onSelect,
        modifier = Modifier
            .fillMaxWidth()
            .transformedHeight(this, spec)
            .minimumVerticalContentPadding(ButtonDefaults.minimumVerticalListContentPadding),
        transformation = SurfaceTransformation(spec),
        icon = icon?.let { res -> { Icon(painterResource(res), contentDescription = null) } },
        secondaryLabel = secondaryLabel?.takeIf { it.isNotEmpty() }?.let { text -> { Text(text, maxLines = 4, overflow = TextOverflow.Ellipsis) } },
        label = { Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
    )
}
