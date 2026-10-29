package net.devemperor.parley.ui.home

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import net.devemperor.parley.R
import net.devemperor.parley.core.cost.CostMeter
import net.devemperor.parley.data.ParleySettings
import net.devemperor.parley.ui.components.EdgeButtonScaffold
import net.devemperor.parley.ui.components.FitText
import net.devemperor.parley.ui.components.Orb
import net.devemperor.parley.ui.components.OrbHandoff
import net.devemperor.parley.ui.components.screenWidth
import net.devemperor.parley.ui.labelRes
import net.devemperor.parley.ui.stringOr

/**
 * The start screen: the orb (tap it, or the edge button, to talk) and three small buttons for the
 * silent text mode, the history and the settings. The line under the orb names provider and model
 * and the month's cost; a tap on it opens the usage statistics.
 */
@Composable
fun HomeScreen(
    settings: ParleySettings,
    monthUsd: Double,
    onUsage: () -> Unit,
    onTalk: () -> Unit,
    onText: () -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
) {
    val provider = settings.providerInfo
    val model = provider.model(settings.modelId())
    EdgeButtonScaffold(
        timeText = { TimeText() },
        edgeButton = {
            EdgeButton(
                onClick = onTalk,
                buttonSize = EdgeButtonSize.ExtraSmall,
                colors = ButtonDefaults.buttonColors(),
            ) {
                Icon(painterResource(R.drawable.ic_mic), contentDescription = null, modifier = Modifier.padding(end = 4.dp).size(20.dp))
                FitText(stringResource(R.string.home_talk))
            }
        },
    ) { padding ->
        val width = screenWidth()
        Column(
            Modifier.fillMaxSize().padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            val talkLabel = stringResource(R.string.home_talk)
            Box(
                Modifier
                    .size(width * 0.32f)
                    .onGloballyPositioned { OrbHandoff.homeBounds = it.boundsInWindow() }
                    .semantics { contentDescription = talkLabel }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClick = onTalk,
                    ),
            ) {
                Orb(phase = null, level = 0f, modifier = Modifier.fillMaxSize())
            }
            // One line that shrinks for languages whose model names run long ("Gründlich", "Minucioso"):
            // a second line pushed the buttons below off their size.
            FitText(
                "${provider.displayName.substringAfterLast(' ')} · ${stringOr(model.labelRes(), model.label)}" +
                    if (settings.showCost && monthUsd > 0) " · ${CostMeter.format(monthUsd)}" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).clickable(onClick = onUsage).padding(vertical = 3.dp),
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SmallAction(R.drawable.ic_chat, stringResource(R.string.home_text_mode), width * 0.17f, onText)
                SmallAction(R.drawable.ic_history, stringResource(R.string.home_history), width * 0.17f, onHistory)
                SmallAction(R.drawable.ic_settings, stringResource(R.string.home_settings), width * 0.17f, onSettings)
            }
        }
    }
}

@Composable
private fun SmallAction(icon: Int, label: String, size: Dp, onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(size)) {
        Icon(painterResource(icon), contentDescription = label, modifier = Modifier.size(size * 0.5f))
    }
}
