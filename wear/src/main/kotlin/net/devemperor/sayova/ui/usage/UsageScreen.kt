package net.devemperor.sayova.ui.usage

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import net.devemperor.sayova.R
import net.devemperor.sayova.core.cost.CostMeter
import net.devemperor.sayova.core.usage.Budget
import net.devemperor.sayova.core.usage.BudgetLevel
import net.devemperor.sayova.core.usage.Usage
import net.devemperor.sayova.core.usage.UsageDay
import net.devemperor.sayova.core.usage.UsageTotal
import net.devemperor.sayova.ui.components.Header
import net.devemperor.sayova.ui.components.Hint
import net.devemperor.sayova.ui.components.ListScreen
import net.devemperor.sayova.ui.components.SubHeader
import net.devemperor.sayova.ui.components.transformedContent
import net.devemperor.sayova.ui.theme.SayovaColors
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Talk time and cost: this month large (against the monthly budget, if one is set), the last two weeks
 * as bars, every month before, and the totals. The costs are Sayova's estimate from the usage the
 * provider reports and its list prices.
 */
@Composable
fun UsageScreen(days: List<UsageDay>, budgetUsd: Int) {
    // Months and days in the watch's language, followed when it changes.
    val locale = LocalConfiguration.current.locales[0]
    val today = LocalDate.now()
    val thisMonth = YearMonth.from(today)
    val month = Usage.month(days, thisMonth)
    val recent = Usage.lastDays(days, today, RECENT_DAYS)
    val months = Usage.months(days).filter { it.first != thisMonth }
    val total = Usage.total(days)
    ListScreen { spec ->
        item { Header(stringResource(R.string.usage_title), spec) }
        item {
            Summary(
                label = thisMonth.month.getDisplayName(TextStyle.FULL_STANDALONE, locale),
                total = month,
                spec = spec,
            )
        }
        if (budgetUsd > 0) item { BudgetBar(month.costUsd, budgetUsd, locale, spec) }
        item { RecentBars(recent, locale, spec) }
        if (months.isNotEmpty()) {
            item { SubHeader(stringResource(R.string.usage_earlier), spec) }
            months.forEach { (yearMonth, sum) ->
                item(key = yearMonth.toString()) { MonthLine(yearMonth, sum, locale, spec) }
            }
        }
        item { SubHeader(stringResource(R.string.usage_all_time), spec) }
        item { TotalLines(total, spec) }
        item { Hint(stringResource(R.string.usage_estimate), spec) }
    }
}

@Composable
private fun TransformingLazyColumnItemScope.Summary(label: String, total: UsageTotal, spec: TransformationSpec) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 2.dp).transformedContent(this, spec),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(CostMeter.format(total.costUsd), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
        Text(
            "${Usage.formatDuration(total.talkMs)} · ${pluralStringResource(R.plurals.usage_conversations, total.conversations, total.conversations)}",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
    }
}

/** How much of the monthly budget is spent, as a bar that turns amber near it and coral once spent. */
@Composable
private fun TransformingLazyColumnItemScope.BudgetBar(spentUsd: Double, budgetUsd: Int, locale: Locale, spec: TransformationSpec) {
    val share = Budget.share(spentUsd, budgetUsd)
    val fill = when (Budget.level(spentUsd, budgetUsd)) {
        BudgetLevel.REACHED -> SayovaColors.Coral
        BudgetLevel.NEAR -> SayovaColors.Amber
        else -> MaterialTheme.colorScheme.primary
    }
    val track = MaterialTheme.colorScheme.surfaceContainerHigh
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 30.dp, vertical = 2.dp).transformedContent(this, spec),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Canvas(Modifier.fillMaxWidth().height(6.dp)) {
            val radius = CornerRadius(size.height / 2f)
            drawRoundRect(track, cornerRadius = radius)
            val filled = size.width * share.coerceIn(0.0, 1.0).toFloat()
            if (filled > 0f) drawRoundRect(fill, size = Size(filled.coerceAtLeast(size.height), size.height), cornerRadius = radius)
        }
        Text(
            stringResource(R.string.usage_budget, Budget.percent(share, locale), Budget.format(budgetUsd)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Minutes per day over the last two weeks; today's bar in the accent colour. */
@Composable
private fun TransformingLazyColumnItemScope.RecentBars(recent: List<Pair<LocalDate, UsageTotal>>, locale: Locale, spec: TransformationSpec) {
    val bar = MaterialTheme.colorScheme.primaryDim
    val todayBar = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.surfaceContainerHigh
    val most = recent.maxOf { it.second.talkMs }.coerceAtLeast(60_000)
    val day = DateTimeFormatter.ofPattern("d MMM", locale)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 4.dp).transformedContent(this, spec),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Canvas(Modifier.fillMaxWidth().height(44.dp)) {
            val slot = size.width / recent.size
            val width = slot * 0.62f
            recent.forEachIndexed { index, (_, sum) ->
                val share = sum.talkMs.toFloat() / most
                val height = if (sum.talkMs > 0) (size.height * share).coerceAtLeast(3.dp.toPx()) else 2.dp.toPx()
                drawRoundRect(
                    color = when {
                        sum.talkMs == 0L -> empty
                        index == recent.lastIndex -> todayBar
                        else -> bar
                    },
                    topLeft = Offset(index * slot + (slot - width) / 2f, size.height - height),
                    size = Size(width, height),
                    cornerRadius = CornerRadius(width / 2f),
                )
            }
        }
        Text(
            stringResource(R.string.usage_recent, recent.first().first.format(day), Usage.formatDuration(most)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun TransformingLazyColumnItemScope.MonthLine(month: YearMonth, total: UsageTotal, locale: Locale, spec: TransformationSpec) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 3.dp).transformedContent(this, spec),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            month.format(DateTimeFormatter.ofPattern("LLLL yyyy", locale)),
            style = MaterialTheme.typography.labelMedium,
        )
        Text(
            "${CostMeter.format(total.costUsd)} · ${Usage.formatDuration(total.talkMs)} · ${total.conversations}×",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TransformingLazyColumnItemScope.TotalLines(total: UsageTotal, spec: TransformationSpec) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 3.dp).transformedContent(this, spec),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            "${CostMeter.format(total.costUsd)} · ${Usage.formatDuration(total.talkMs)}",
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            pluralStringResource(R.plurals.usage_conversations, total.conversations, total.conversations),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (total.conversations > 0) {
            Text(
                stringResource(R.string.usage_average, CostMeter.format(total.costPerConversation), CostMeter.format(total.costPerMinute)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private const val RECENT_DAYS = 14
