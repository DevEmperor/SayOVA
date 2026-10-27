package net.devemperor.parley.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import net.devemperor.parley.core.prompt.AnswerLanguage
import net.devemperor.parley.core.prompt.Persona
import net.devemperor.parley.core.provider.Provider
import net.devemperor.parley.core.provider.ProviderCatalog
import net.devemperor.parley.core.provider.ProviderId
import java.io.IOException
import java.time.YearMonth

/** Whether the user's voice may cut the answer short. */
enum class BargeIn(val label: String, val description: String) {
    AUTO("With headphones", "On the watch speaker, tap the orb instead"),
    ON("Always", "The watch may hear its own voice"),
    OFF("Never", "Tap the orb to interrupt"),
}

data class ParleySettings(
    val provider: ProviderId = ProviderId.GEMINI,
    val geminiModel: String = ProviderCatalog.gemini.defaultModel.id,
    val geminiVoice: String = ProviderCatalog.gemini.defaultVoice,
    val openAiModel: String = ProviderCatalog.openAi.defaultModel.id,
    val openAiVoice: String = ProviderCatalog.openAi.defaultVoice,
    val language: AnswerLanguage = AnswerLanguage.AUTO,
    val persona: Persona = Persona.FRIENDLY,
    val customPersona: String = "",
    val webSearch: Boolean = true,
    val bargeIn: BargeIn = BargeIn.AUTO,
    val pushToTalk: Boolean = false,
    /** Seconds of silence after which a conversation ends by itself; 0 = never. */
    val autoEndSeconds: Int = 20,
    val haptics: Boolean = true,
    val showCost: Boolean = true,
    /** Days conversations are kept; 0 = not saved, -1 = forever. */
    val historyDays: Int = 30,
    val startOnLaunch: Boolean = false,
    val costMonth: String = "",
    val costMonthUsd: Double = 0.0,
) {
    val providerInfo: Provider get() = ProviderCatalog.get(provider)

    fun modelId(id: ProviderId = provider): String = when (id) {
        ProviderId.GEMINI -> geminiModel
        ProviderId.OPENAI -> openAiModel
    }

    fun voiceId(id: ProviderId = provider): String = when (id) {
        ProviderId.GEMINI -> geminiVoice
        ProviderId.OPENAI -> openAiVoice
    }

    /** Spend of the current calendar month (the stored figure belongs to another month otherwise). */
    fun monthUsd(now: YearMonth = YearMonth.now()): Double = if (costMonth == now.toString()) costMonthUsd else 0.0

    companion object {
        val AUTO_END_OPTIONS = listOf(10, 20, 30, 60, 0)
        val HISTORY_OPTIONS = listOf(0, 7, 30, -1)
    }
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val provider = stringPreferencesKey("provider")
        val geminiModel = stringPreferencesKey("gemini_model")
        val geminiVoice = stringPreferencesKey("gemini_voice")
        val openAiModel = stringPreferencesKey("openai_model")
        val openAiVoice = stringPreferencesKey("openai_voice")
        val language = stringPreferencesKey("language")
        val persona = stringPreferencesKey("persona")
        val customPersona = stringPreferencesKey("custom_persona")
        val webSearch = booleanPreferencesKey("web_search")
        val bargeIn = stringPreferencesKey("barge_in")
        val pushToTalk = booleanPreferencesKey("push_to_talk")
        val autoEnd = intPreferencesKey("auto_end_seconds")
        val haptics = booleanPreferencesKey("haptics")
        val showCost = booleanPreferencesKey("show_cost")
        val historyDays = intPreferencesKey("history_days")
        val startOnLaunch = booleanPreferencesKey("start_on_launch")
        val costMonth = stringPreferencesKey("cost_month")
        val costMonthUsd = doublePreferencesKey("cost_month_usd")
    }

    val settings: Flow<ParleySettings> = context.dataStore.data
        .catch { e ->
            if (e is IOException) {
                Log.w("Parley", "Settings unreadable, using defaults", e)
                emit(emptyPreferences())
            } else {
                throw e
            }
        }
        .map(::fromPreferences)

    suspend fun current(): ParleySettings = settings.first()

    suspend fun update(transform: (ParleySettings) -> ParleySettings) {
        context.dataStore.edit { prefs ->
            val updated = transform(fromPreferences(prefs))
            prefs[Keys.provider] = updated.provider.key
            prefs[Keys.geminiModel] = updated.geminiModel
            prefs[Keys.geminiVoice] = updated.geminiVoice
            prefs[Keys.openAiModel] = updated.openAiModel
            prefs[Keys.openAiVoice] = updated.openAiVoice
            prefs[Keys.language] = updated.language.name
            prefs[Keys.persona] = updated.persona.name
            prefs[Keys.customPersona] = updated.customPersona
            prefs[Keys.webSearch] = updated.webSearch
            prefs[Keys.bargeIn] = updated.bargeIn.name
            prefs[Keys.pushToTalk] = updated.pushToTalk
            prefs[Keys.autoEnd] = updated.autoEndSeconds
            prefs[Keys.haptics] = updated.haptics
            prefs[Keys.showCost] = updated.showCost
            prefs[Keys.historyDays] = updated.historyDays
            prefs[Keys.startOnLaunch] = updated.startOnLaunch
            prefs[Keys.costMonth] = updated.costMonth
            prefs[Keys.costMonthUsd] = updated.costMonthUsd
        }
    }

    suspend fun addCost(usd: Double) = update { s ->
        val month = YearMonth.now().toString()
        s.copy(costMonth = month, costMonthUsd = s.monthUsd() + usd)
    }

    private fun fromPreferences(prefs: Preferences): ParleySettings {
        val d = ParleySettings()
        return ParleySettings(
            provider = ProviderId.fromKey(prefs[Keys.provider]) ?: d.provider,
            geminiModel = ProviderCatalog.gemini.model(prefs[Keys.geminiModel]).id,
            geminiVoice = ProviderCatalog.gemini.voice(prefs[Keys.geminiVoice]).id,
            openAiModel = ProviderCatalog.openAi.model(prefs[Keys.openAiModel]).id,
            openAiVoice = ProviderCatalog.openAi.voice(prefs[Keys.openAiVoice]).id,
            language = enumOr(prefs[Keys.language], d.language),
            persona = enumOr(prefs[Keys.persona], d.persona),
            customPersona = prefs[Keys.customPersona] ?: d.customPersona,
            webSearch = prefs[Keys.webSearch] ?: d.webSearch,
            bargeIn = enumOr(prefs[Keys.bargeIn], d.bargeIn),
            pushToTalk = prefs[Keys.pushToTalk] ?: d.pushToTalk,
            autoEndSeconds = prefs[Keys.autoEnd]?.takeIf { it in ParleySettings.AUTO_END_OPTIONS } ?: d.autoEndSeconds,
            haptics = prefs[Keys.haptics] ?: d.haptics,
            showCost = prefs[Keys.showCost] ?: d.showCost,
            historyDays = prefs[Keys.historyDays]?.takeIf { it in ParleySettings.HISTORY_OPTIONS } ?: d.historyDays,
            startOnLaunch = prefs[Keys.startOnLaunch] ?: d.startOnLaunch,
            costMonth = prefs[Keys.costMonth] ?: d.costMonth,
            costMonthUsd = prefs[Keys.costMonthUsd] ?: d.costMonthUsd,
        )
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default
}
