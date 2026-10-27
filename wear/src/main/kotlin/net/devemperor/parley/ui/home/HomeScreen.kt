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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import net.devemperor.parley.ui.components.Orb

/**
 * The start screen: the orb (tap it, or the edge button, to talk) and three small buttons for the
 * silent text mode, the history and the settings.
 */
@Composable
fun HomeScreen(
    settings: ParleySettings,
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
                buttonSize = EdgeButtonSize.Medium,
                colors = ButtonDefaults.buttonColors(),
            ) {
                Icon(painterResource(R.drawable.ic_mic), contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                Text(stringResource(R.string.home_talk))
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            val talkLabel = stringResource(R.string.home_talk)
            Box(
                Modifier
                    .size(84.dp)
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
            Text(
                "${provider.displayName.substringAfterLast(' ')} · ${model.label}" +
                    if (settings.showCost && settings.monthUsd() > 0) " · ${CostMeter.format(settings.monthUsd())}" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallAction(R.drawable.ic_chat, stringResource(R.string.home_text_mode), onText)
                SmallAction(R.drawable.ic_history, stringResource(R.string.home_history), onHistory)
                SmallAction(R.drawable.ic_settings, stringResource(R.string.home_settings), onSettings)
            }
        }
    }
}

@Composable
private fun SmallAction(icon: Int, label: String, onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(painterResource(icon), contentDescription = label, modifier = Modifier.size(20.dp))
    }
}
