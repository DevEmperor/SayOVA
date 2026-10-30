package net.devemperor.parley.core.usage

import java.text.NumberFormat
import java.util.Locale

/** Where this month's cost stands against the budget; ordered, so a later level is a worse one. */
enum class BudgetLevel { NONE, UNDER, NEAR, REACHED }

/**
 * The monthly budget the user may set, in whole US dollars like the cost estimate it is measured
 * against: from [NEAR_SHARE] of it on Parley warns, and once it is spent Parley asks before each
 * conversation.
 */
object Budget {
    const val NEAR_SHARE = 0.8

    /** [budgetUsd] 0 is no budget. */
    fun level(spentUsd: Double, budgetUsd: Int): BudgetLevel = when {
        budgetUsd <= 0 -> BudgetLevel.NONE
        spentUsd >= budgetUsd -> BudgetLevel.REACHED
        spentUsd >= budgetUsd * NEAR_SHARE -> BudgetLevel.NEAR
        else -> BudgetLevel.UNDER
    }

    /** How much of the budget is spent, 0 to 1 and beyond; 0 without one. */
    fun share(spentUsd: Double, budgetUsd: Int): Double = if (budgetUsd > 0) spentUsd / budgetUsd else 0.0

    /** "$5", without the estimate's tilde: the budget is an amount the user chose. */
    fun format(budgetUsd: Int): String = "\$$budgetUsd"

    /** [share] as a percentage the way [locale] writes one: "80%", "80 %", "%80". */
    fun percent(share: Double, locale: Locale): String = NumberFormat.getPercentInstance(locale).format(share)
}
