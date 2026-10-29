package net.devemperor.parley.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import net.devemperor.parley.R
import net.devemperor.parley.core.prompt.AnswerLanguage
import net.devemperor.parley.core.prompt.Persona
import net.devemperor.parley.core.provider.Model
import net.devemperor.parley.core.provider.Voice
import net.devemperor.parley.core.tools.WatchFeature
import net.devemperor.parley.data.BargeIn
import net.devemperor.parley.net.Transport

/*
 * What the watch calls the choices that core and the network define, in the app's languages. Core
 * keeps English names because they also go into the instructions for the model; the screens show
 * these instead. Every value is named here explicitly: a lookup by name would hide the strings from
 * the release build's resource shrinking.
 */

/** [res] in the app's language, or [fallback] for a value without a translation. */
@Composable
fun stringOr(@StringRes res: Int?, fallback: String): String = if (res != null) stringResource(res) else fallback

@StringRes
fun Persona.labelRes(): Int = when (this) {
    Persona.FRIENDLY -> R.string.persona_friendly
    Persona.CONCISE -> R.string.persona_concise
    Persona.TUTOR -> R.string.persona_tutor
    Persona.LANGUAGE_PARTNER -> R.string.persona_language_partner
    Persona.CUSTOM -> R.string.persona_custom
}

@StringRes
fun Persona.descriptionRes(): Int = when (this) {
    Persona.FRIENDLY -> R.string.persona_friendly_description
    Persona.CONCISE -> R.string.persona_concise_description
    Persona.TUTOR -> R.string.persona_tutor_description
    Persona.LANGUAGE_PARTNER -> R.string.persona_language_partner_description
    Persona.CUSTOM -> R.string.persona_custom_description
}

/** Pinned languages go by their own name ("Deutsch", "日本語"), which needs no translation. */
@StringRes
fun AnswerLanguage.labelRes(): Int? = if (this == AnswerLanguage.AUTO) R.string.language_auto else null

@StringRes
fun BargeIn.labelRes(): Int = when (this) {
    BargeIn.AUTO -> R.string.barge_in_auto
    BargeIn.ON -> R.string.barge_in_on
    BargeIn.OFF -> R.string.barge_in_off
}

@StringRes
fun BargeIn.descriptionRes(): Int = when (this) {
    BargeIn.AUTO -> R.string.barge_in_auto_description
    BargeIn.ON -> R.string.barge_in_on_description
    BargeIn.OFF -> R.string.barge_in_off_description
}

/** The models of ProviderCatalog by id; a model added there without a name here keeps its English one. */
@StringRes
fun Model.labelRes(): Int? = when (id) {
    "gemini-3.8-live", "gpt-realtime-2.1-mini" -> R.string.model_fast
    "gemini-3.8-live-extended-thinking" -> R.string.model_thorough
    "gpt-realtime-2.1" -> R.string.model_best
    else -> null
}

@StringRes
fun Model.descriptionRes(): Int? = when (id) {
    "gemini-3.8-live" -> R.string.model_gemini_fast_description
    "gemini-3.8-live-extended-thinking" -> R.string.model_gemini_thorough_description
    "gpt-realtime-2.1-mini" -> R.string.model_openai_fast_description
    "gpt-realtime-2.1" -> R.string.model_openai_best_description
    else -> null
}

/** The providers' one-word voice descriptions. */
@StringRes
fun Voice.descriptionRes(): Int? = when (description) {
    "Recommended" -> R.string.voice_recommended
    "Firm" -> R.string.voice_firm
    "Upbeat" -> R.string.voice_upbeat
    "Informative" -> R.string.voice_informative
    "Bright" -> R.string.voice_bright
    "Excitable" -> R.string.voice_excitable
    "Youthful" -> R.string.voice_youthful
    "Breezy" -> R.string.voice_breezy
    "Easy-going" -> R.string.voice_easy_going
    "Breathy" -> R.string.voice_breathy
    "Clear" -> R.string.voice_clear
    "Smooth" -> R.string.voice_smooth
    "Gravelly" -> R.string.voice_gravelly
    "Soft" -> R.string.voice_soft
    "Even" -> R.string.voice_even
    "Mature" -> R.string.voice_mature
    "Forward" -> R.string.voice_forward
    "Friendly" -> R.string.voice_friendly
    "Casual" -> R.string.voice_casual
    "Gentle" -> R.string.voice_gentle
    "Lively" -> R.string.voice_lively
    "Knowledgeable" -> R.string.voice_knowledgeable
    "Warm" -> R.string.voice_warm
    else -> null
}

@StringRes
fun Transport.labelRes(): Int = when (this) {
    Transport.WIFI -> R.string.transport_wifi
    Transport.CELLULAR -> R.string.transport_cellular
    Transport.BLUETOOTH -> R.string.transport_bluetooth
    Transport.OTHER -> R.string.transport_other
    Transport.NONE -> R.string.transport_none
}

@StringRes
fun WatchFeature.labelRes(): Int = when (this) {
    WatchFeature.LOCATION -> R.string.feature_location
    WatchFeature.HEART_RATE -> R.string.feature_heart_rate
    WatchFeature.STEPS -> R.string.feature_steps
    WatchFeature.CALENDAR -> R.string.feature_calendar
    WatchFeature.WEATHER -> R.string.feature_weather
    WatchFeature.PRESSURE -> R.string.feature_pressure
    WatchFeature.ALARM -> R.string.feature_alarm
    WatchFeature.MUSIC -> R.string.feature_music
    WatchFeature.CALLS -> R.string.feature_calls
    WatchFeature.APPS -> R.string.feature_apps
    WatchFeature.NOTIFICATIONS -> R.string.feature_notifications
}


/** What the conversation shows while the feature is in use ("Finding your location…"). */
@StringRes
fun WatchFeature.workingRes(): Int = when (this) {
    WatchFeature.LOCATION -> R.string.notice_location
    WatchFeature.HEART_RATE -> R.string.notice_heart_rate
    WatchFeature.STEPS -> R.string.notice_steps
    WatchFeature.CALENDAR -> R.string.notice_calendar
    WatchFeature.WEATHER -> R.string.notice_weather
    WatchFeature.PRESSURE -> R.string.notice_pressure
    WatchFeature.ALARM -> R.string.notice_alarm
    WatchFeature.MUSIC -> R.string.notice_music
    WatchFeature.CALLS -> R.string.notice_contacts
    WatchFeature.APPS -> R.string.notice_apps
    WatchFeature.NOTIFICATIONS -> R.string.notice_notifications
}

@DrawableRes
fun WatchFeature.iconRes(): Int = when (this) {
    WatchFeature.LOCATION -> R.drawable.ic_location_on
    WatchFeature.HEART_RATE -> R.drawable.ic_favorite
    WatchFeature.STEPS -> R.drawable.ic_directions_walk
    WatchFeature.CALENDAR -> R.drawable.ic_event
    WatchFeature.WEATHER -> R.drawable.ic_partly_cloudy_day
    WatchFeature.PRESSURE -> R.drawable.ic_landscape
    WatchFeature.ALARM -> R.drawable.ic_alarm
    WatchFeature.MUSIC -> R.drawable.ic_music_note
    WatchFeature.CALLS -> R.drawable.ic_call
    WatchFeature.APPS -> R.drawable.ic_apps
    WatchFeature.NOTIFICATIONS -> R.drawable.ic_notifications
}
