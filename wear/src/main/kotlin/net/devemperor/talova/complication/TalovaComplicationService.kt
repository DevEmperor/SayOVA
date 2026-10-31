package net.devemperor.talova.complication

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationText
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.GoalProgressComplicationData
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.PhotoImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.data.WeightedElementsComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import net.devemperor.talova.MainActivity
import net.devemperor.talova.R
import net.devemperor.talova.core.usage.Budget
import net.devemperor.talova.core.usage.Usage
import net.devemperor.talova.talova
import net.devemperor.talova.ui.settings.AppLanguages
import java.time.YearMonth

/**
 * A watch-face shortcut: one tap and Talova is listening. Every kind of complication a watch face can
 * offer gets one, so Talova fits any slot: where the face shows colour the orb itself, elsewhere its
 * one-colour outline ([R.drawable.ic_talova_mono]) in the face's tint. The kinds that show a value (an
 * arc, a goal, a pie) show this month's cost against the monthly budget, empty without one.
 */
class TalovaComplicationService : SuspendingComplicationDataSourceService() {

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val app = talova
        app.usage.awaitLoaded()
        val budget = app.settings.current().budgetUsd
        return data(request.complicationType, Usage.month(app.usage.days.value, YearMonth.now()).costUsd, budget)
    }

    /** In the watch face's picker: a budget two thirds spent, so the kinds with a value show one. */
    override fun getPreviewData(type: ComplicationType): ComplicationData? = data(type, spentUsd = 3.4, budgetUsd = 5)

    private fun data(type: ComplicationType, spentUsd: Double, budgetUsd: Int): ComplicationData? {
        val tap = PendingIntent.getActivity(
            this, 0, MainActivity.talkIntent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val strings = AppLanguages.localized(this)
        val talk = text(strings.getString(R.string.complication_short))
        val mono = MonochromaticImage.Builder(Icon.createWithResource(this, R.drawable.ic_talova_mono)).build()
        val orbIcon = Icon.createWithResource(this, R.drawable.orb_disc)
        // In ambient mode a full-colour image would burn in; the outline stands in for it.
        val orb = SmallImage.Builder(orbIcon, SmallImageType.PHOTO)
            .setAmbientImage(Icon.createWithResource(this, R.drawable.ic_talova_mono))
            .build()
        val share = Budget.share(spentUsd, budgetUsd)
        val description = text(
            if (budgetUsd > 0) {
                strings.getString(R.string.complication_label) + ", " + strings.getString(
                    R.string.usage_budget,
                    Budget.percent(share, strings.resources.configuration.locales[0]),
                    Budget.format(budgetUsd),
                )
            } else {
                strings.getString(R.string.complication_description)
            },
        )
        // Without a budget there is nothing to fill: an empty arc of one.
        val value = if (budgetUsd > 0) spentUsd.toFloat().coerceAtMost(budgetUsd.toFloat()) else 0f
        val max = if (budgetUsd > 0) budgetUsd.toFloat() else 1f
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(talk, description)
                .setMonochromaticImage(mono).setTapAction(tap).build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(text(strings.getString(R.string.complication_label)), description)
                .setMonochromaticImage(mono).setSmallImage(orb).setTapAction(tap).build()
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(value, 0f, max, description)
                .setText(talk).setMonochromaticImage(mono).setTapAction(tap).build()
            ComplicationType.MONOCHROMATIC_IMAGE -> MonochromaticImageComplicationData.Builder(mono, description)
                .setTapAction(tap).build()
            ComplicationType.SMALL_IMAGE -> SmallImageComplicationData.Builder(orb, description)
                .setTapAction(tap).build()
            ComplicationType.PHOTO_IMAGE -> PhotoImageComplicationData.Builder(orbIcon, description)
                .setTapAction(tap).build()
            // A goal and a pie came with Wear OS 4 (API 33); older watches never ask for them.
            else -> if (Build.VERSION.SDK_INT >= 33) {
                fromWearOs4(type, value, max, budgetUsd > 0, talk, mono, description, tap)
            } else {
                null
            }
        }
    }

    @RequiresApi(33)
    private fun fromWearOs4(
        type: ComplicationType,
        value: Float,
        max: Float,
        hasBudget: Boolean,
        talk: ComplicationText,
        mono: MonochromaticImage,
        description: ComplicationText,
        tap: PendingIntent,
    ): ComplicationData? = when (type) {
        ComplicationType.GOAL_PROGRESS -> GoalProgressComplicationData.Builder(value, max, description)
            .setText(talk).setMonochromaticImage(mono).setTapAction(tap).build()
        ComplicationType.WEIGHTED_ELEMENTS -> {
            // Spent in teal against what is left; without a budget one teal piece.
            val elements = if (hasBudget) {
                listOf(
                    WeightedElementsComplicationData.Element(value.coerceAtLeast(MIN_WEIGHT), SPENT_COLOR),
                    WeightedElementsComplicationData.Element((max - value).coerceAtLeast(MIN_WEIGHT), LEFT_COLOR),
                )
            } else {
                listOf(WeightedElementsComplicationData.Element(1f, SPENT_COLOR))
            }
            WeightedElementsComplicationData.Builder(elements, description)
                .setText(talk).setMonochromaticImage(mono).setTapAction(tap).build()
        }
        else -> null
    }

    private fun text(value: String): ComplicationText = PlainComplicationText.Builder(value).build()

    companion object {
        private const val SPENT_COLOR = 0xFF2EC4B6.toInt()
        private const val LEFT_COLOR = 0xFF2A3140.toInt()
        /** Weights must be above zero. */
        private const val MIN_WEIGHT = 0.001f

        /** Asks watch faces showing Talova to fetch it again: the month's cost changed, or the budget. */
        fun refresh(context: Context) {
            runCatching {
                ComplicationDataSourceUpdateRequester.create(
                    context.applicationContext,
                    ComponentName(context.applicationContext, TalovaComplicationService::class.java),
                ).requestUpdateAll()
            }
        }
    }
}
