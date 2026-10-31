package net.devemperor.sayova.ui.home

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
import net.devemperor.sayova.R
import net.devemperor.sayova.core.cost.CostMeter
import net.devemperor.sayova.core.usage.Budget
import net.devemperor.sayova.core.usage.BudgetLevel
import net.devemperor.sayova.data.SayovaSettings
import net.devemperor.sayova.ui.components.EdgeButtonScaffold
import net.devemperor.sayova.ui.components.FitText
import net.devemperor.sayova.ui.components.Orb
import net.devemperor.sayova.ui.components.OrbHandoff
import net.devemperor.sayova.ui.components.screenWidth
import net.devemperor.sayova.ui.labelRes
import net.devemperor.sayova.ui.stringOr
import net.devemperor.sayova.ui.theme.SayovaColors
import net.devemperor.sayova.ui.tour.TourTarget
import net.devemperor.sayova.ui.tour.tourTarget

/**
 * The start screen: the orb (tap it, or the edge button, to talk) and round buttons for the
 * interpreter, the history and the settings. The line under the orb names provider and model
 * and the month's cost, near the monthly budget against it; a tap on it opens the usage statistics.
 */
@Composable
fun HomeScreen(
    settings: SayovaSettings,
    monthUsd: Double,
    onUsage: () -> Unit,
    onTalk: () -> Unit,
    onInterpreter: () -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
) {
    val provider = settings.providerInfo
    val budget = Budget.level(monthUsd, settings.budgetUsd)
    // The model the next conversation will use: near the budget perhaps the cheaper one (see SessionService).
    val model = if (settings.budgetSave && budget >= BudgetLevel.NEAR) provider.economyModel else provider.model(settings.modelId())
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
                    .size(width * 0.26f)
                    .onGloballyPositioned { OrbHandoff.homeBounds = it.boundsInWindow() }
                    .tourTarget(TourTarget.HOME_ORB)
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
            // a second line pushed the buttons below off their size. Near the budget the provider's name
            // gives way to the budget, in amber and then coral, even with the cost hidden: it is a
            // warning then.
            val modelLabel = stringOr(model.labelRes(), model.label)
            val line = if (budget == BudgetLevel.NEAR || budget == BudgetLevel.REACHED) {
                "$modelLabel · ${CostMeter.format(monthUsd)} / ${Budget.format(settings.budgetUsd)}"
            } else {
                "${provider.displayName.substringAfterLast(' ')} · $modelLabel" +
                    if (settings.showCost && monthUsd > 0) " · ${CostMeter.format(monthUsd)}" else ""
            }
            FitText(
                line,
                style = MaterialTheme.typography.labelSmall,
                color = when (budget) {
                    BudgetLevel.REACHED -> SayovaColors.Coral
                    BudgetLevel.NEAR -> SayovaColors.Amber
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp).tourTarget(TourTarget.HOME_LINE).clickable(onClick = onUsage).padding(vertical = 3.dp),
            )
            Spacer(Modifier.height(4.dp))
            // Large enough to hit; at the size of a small icon they were easy to miss.
            val size = width * 0.2f
            Row(horizontalArrangement = Arrangement.spacedBy(width * 0.04f)) {
                Action(R.drawable.ic_translate, stringResource(R.string.interpreter_title), size, TourTarget.HOME_INTERPRETER, onInterpreter)
                Action(R.drawable.ic_history, stringResource(R.string.home_history), size, TourTarget.HOME_HISTORY, onHistory)
                Action(R.drawable.ic_settings, stringResource(R.string.home_settings), size, TourTarget.HOME_SETTINGS, onSettings)
            }
        }
    }
}

@Composable
private fun Action(icon: Int, label: String, size: Dp, target: TourTarget, onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(size).tourTarget(target, visual = size)) {
        Icon(painterResource(icon), contentDescription = label, modifier = Modifier.size(size * 0.45f))
    }
}
