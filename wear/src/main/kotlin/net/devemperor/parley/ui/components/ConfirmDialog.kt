package net.devemperor.parley.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.Text
import net.devemperor.parley.R
import net.devemperor.parley.ui.theme.ParleyColors

/**
 * Asks before something is deleted or removed. Material's own dialog pairs a rounded square "cancel"
 * with a "confirm" cut to the curve of the screen, which read as two unrelated shapes; here both are
 * the same circle, cancel muted and confirm in the warning colour of what it does.
 */
@Composable
fun ConfirmDialog(
    visible: Boolean,
    title: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    text: String? = null,
    @DrawableRes icon: Int = R.drawable.ic_delete,
) {
    AlertDialog(
        visible = visible,
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource(icon), contentDescription = null) },
        title = { Text(title) },
        text = text?.let { { Text(it, textAlign = TextAlign.Center, maxLines = 4, overflow = TextOverflow.Ellipsis) } },
        dismissButton = {
            FilledTonalIconButton(
                onClick = onDismiss,
                modifier = Modifier.size(BUTTON),
                shapes = IconButtonDefaults.shapes(CircleShape),
            ) {
                Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.action_cancel))
            }
        },
        confirmButton = {
            FilledIconButton(
                onClick = onConfirm,
                modifier = Modifier.size(BUTTON),
                shapes = IconButtonDefaults.shapes(CircleShape),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = ParleyColors.Coral, contentColor = Color.Black),
            ) {
                Icon(painterResource(R.drawable.ic_check), contentDescription = stringResource(R.string.action_confirm))
            }
        },
    )
}

private val BUTTON = 52.dp
