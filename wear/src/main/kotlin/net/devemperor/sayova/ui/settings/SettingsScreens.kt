package net.devemperor.sayova.ui.settings

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.devemperor.sayova.BuildConfig
import net.devemperor.sayova.R
import net.devemperor.sayova.audio.VoiceSamples
import net.devemperor.sayova.core.cost.CostMeter
import net.devemperor.sayova.core.prompt.AnswerLanguage
import net.devemperor.sayova.core.prompt.InterpretLanguage
import net.devemperor.sayova.core.prompt.Persona
import net.devemperor.sayova.core.provider.ProviderCatalog
import net.devemperor.sayova.core.provider.ProviderId
import net.devemperor.sayova.core.tools.WatchFeature
import net.devemperor.sayova.core.usage.Budget
import net.devemperor.sayova.core.usage.Usage
import net.devemperor.sayova.core.usage.UsageTotal
import net.devemperor.sayova.data.BargeIn
import net.devemperor.sayova.data.SayovaSettings
import net.devemperor.sayova.features.WatchFeatures
import net.devemperor.sayova.ui.components.ConfirmDialog
import net.devemperor.sayova.ui.components.Header
import net.devemperor.sayova.ui.components.Hint
import net.devemperor.sayova.ui.components.ListScreen
import net.devemperor.sayova.ui.components.MenuButton
import net.devemperor.sayova.ui.components.RadioItem
import net.devemperor.sayova.ui.components.SubHeader
import net.devemperor.sayova.ui.components.SwitchItem
import net.devemperor.sayova.ui.components.rememberTextInput
import net.devemperor.sayova.ui.descriptionRes
import net.devemperor.sayova.ui.labelRes
import net.devemperor.sayova.ui.stringOr

typealias Update = ((SayovaSettings) -> SayovaSettings) -> Unit

/** Options that are a single choice from a list get one generic screen each. */
enum class Choice { MODEL, VOICE, LANGUAGE, APP_LANGUAGE, PERSONA, BARGE_IN, AUTO_END, HISTORY, BUDGET, INTERPRET_FIRST, INTERPRET_SECOND }

private enum class SampleState { LOADING, PLAYING, FAILED }

private const val SAMPLE_FAILED_MS = 3_000L

@Composable
fun SettingsScreen(
    settings: SayovaSettings,
    connected: Set<ProviderId>,
    onUpdate: Update,
    onOpenProvider: () -> Unit,
    onOpenChoice: (Choice) -> Unit,
    onDeleteHistory: () -> Unit,
    onOpenAbout: () -> Unit,
    monthUsage: UsageTotal,
    onOpenUsage: () -> Unit,
    memoryFacts: Int,
    onOpenMemory: () -> Unit,
    onOpenFeatures: () -> Unit,
) {
    val provider = settings.providerInfo
    var confirmDelete by remember { mutableStateOf(false) }
    ListScreen { spec ->
        item { Header(stringResource(R.string.settings_title), spec) }
        item {
            MenuButton(
                label = provider.displayName,
                secondaryLabel = if (provider.id in connected) stringResource(R.string.settings_provider_connected) else stringResource(R.string.settings_provider_missing),
                icon = R.drawable.ic_key,
                spec = spec,
                onClick = onOpenProvider,
            )
        }
        item {
            val model = provider.model(settings.modelId())
            val label = stringOr(model.labelRes(), model.label)
            val description = stringOr(model.descriptionRes(), model.description)
            MenuButton(stringResource(R.string.settings_model), spec, { onOpenChoice(Choice.MODEL) }, "$label · $description", R.drawable.ic_bolt)
        }
        item {
            val voice = provider.voice(settings.voiceId())
            MenuButton(stringResource(R.string.settings_voice), spec, { onOpenChoice(Choice.VOICE) }, voice.id.replaceFirstChar(Char::uppercase), R.drawable.ic_record_voice_over)
        }
        item {
            val language = stringOr(settings.language.labelRes(), settings.language.label)
            MenuButton(stringResource(R.string.settings_language), spec, { onOpenChoice(Choice.LANGUAGE) }, language, R.drawable.ic_translate)
        }
        item { MenuButton(stringResource(R.string.settings_persona), spec, { onOpenChoice(Choice.PERSONA) }, stringResource(settings.persona.labelRes()), R.drawable.ic_face) }
        item {
            val summary = when {
                !settings.memory -> stringResource(R.string.memory_off)
                memoryFacts == 0 -> stringResource(R.string.memory_none)
                else -> pluralStringResource(R.plurals.memory_facts, memoryFacts, memoryFacts)
            }
            MenuButton(stringResource(R.string.memory_title), spec, onOpenMemory, summary, R.drawable.ic_psychology)
        }

        item { SubHeader(stringResource(R.string.settings_conversation), spec) }
        item {
            SwitchItem(
                stringResource(R.string.settings_web_search), settings.webSearch, { v -> onUpdate { it.copy(webSearch = v) } }, spec,
                icon = R.drawable.ic_travel_explore,
            )
        }
        item {
            // Eleven names no longer fit under the label; how many are on does, of those this watch offers.
            val context = LocalContext.current
            val usable = remember { WatchFeature.entries.filter { WatchFeatures.available(context, it) } }
            val on = settings.features.filter { it in usable }
            val summary = if (on.isEmpty()) stringResource(R.string.features_none) else stringResource(R.string.features_count, on.size, usable.size)
            MenuButton(stringResource(R.string.features_title), spec, onOpenFeatures, summary, R.drawable.ic_watch)
        }
        item { MenuButton(stringResource(R.string.settings_barge_in), spec, { onOpenChoice(Choice.BARGE_IN) }, stringResource(settings.bargeIn.labelRes()), R.drawable.ic_front_hand) }
        item {
            SwitchItem(
                stringResource(R.string.settings_push_to_talk), settings.pushToTalk, { v -> onUpdate { it.copy(pushToTalk = v) } }, spec,
                icon = R.drawable.ic_touch_app,
            )
        }
        item { MenuButton(stringResource(R.string.settings_auto_end), spec, { onOpenChoice(Choice.AUTO_END) }, autoEndLabel(settings.autoEndSeconds), R.drawable.ic_timer) }
        item {
            SwitchItem(
                stringResource(R.string.settings_start_on_launch), settings.startOnLaunch, { v -> onUpdate { it.copy(startOnLaunch = v) } }, spec,
                icon = R.drawable.ic_mic,
            )
        }

        item { SubHeader(stringResource(R.string.settings_costs), spec) }
        item {
            MenuButton(
                stringResource(R.string.settings_usage), spec, onOpenUsage,
                stringResource(R.string.settings_usage_summary, CostMeter.format(monthUsage.costUsd), Usage.formatDuration(monthUsage.talkMs)),
                R.drawable.ic_bar_chart,
            )
        }
        item { MenuButton(stringResource(R.string.settings_budget), spec, { onOpenChoice(Choice.BUDGET) }, budgetLabel(settings.budgetUsd), R.drawable.ic_savings) }
        item {
            SwitchItem(
                stringResource(R.string.settings_show_cost), settings.showCost, { v -> onUpdate { it.copy(showCost = v) } }, spec,
                icon = R.drawable.ic_payments,
            )
        }

        item { SubHeader(stringResource(R.string.settings_privacy), spec) }
        item { MenuButton(stringResource(R.string.settings_history), spec, { onOpenChoice(Choice.HISTORY) }, historyLabel(settings.historyDays), R.drawable.ic_history) }
        item { MenuButton(stringResource(R.string.settings_delete_history), spec, { confirmDelete = true }, icon = R.drawable.ic_delete) }

        item { SubHeader(stringResource(R.string.settings_general), spec) }
        item { SwitchItem(stringResource(R.string.settings_haptics), settings.haptics, { v -> onUpdate { it.copy(haptics = v) } }, spec, icon = R.drawable.ic_vibration) }
        if (AppLanguages.supported) {
            item {
                val chosen = AppLanguages.current(LocalContext.current)
                MenuButton(
                    stringResource(R.string.settings_app_language), spec, { onOpenChoice(Choice.APP_LANGUAGE) },
                    chosen?.let(AppLanguages::name) ?: stringResource(R.string.app_language_system), R.drawable.ic_language,
                )
            }
        }
        item { MenuButton(stringResource(R.string.settings_about), spec, onOpenAbout, "${stringResource(R.string.app_name)} ${BuildConfig.VERSION_NAME}", R.drawable.ic_info) }
    }
    ConfirmDialog(
        visible = confirmDelete,
        title = stringResource(R.string.settings_delete_history_confirm),
        onConfirm = {
            confirmDelete = false
            onDeleteHistory()
        },
        onDismiss = { confirmDelete = false },
    )
}

@Composable
fun ChoiceScreen(choice: Choice, settings: SayovaSettings, onUpdate: Update, onDone: () -> Unit) {
    val context = LocalContext.current
    val deviceLanguage = LocalConfiguration.current.locales[0].language
    val provider = settings.providerInfo
    val select: ((SayovaSettings) -> SayovaSettings) -> Unit = { transform ->
        onUpdate(transform)
        onDone()
    }
    val customInput = rememberTextInput(stringResource(R.string.persona_custom_label)) { text ->
        if (text != null) select { it.copy(persona = Persona.CUSTOM, customPersona = text) }
    }
    // The voice sample being fetched or played; leaving the screen cancels it with the scope.
    val samples = remember { VoiceSamples(context) }
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var sample by remember { mutableStateOf<Pair<String, SampleState>?>(null) }
    var sampleJob by remember { mutableStateOf<Job?>(null) }
    fun hear(voice: String) {
        sampleJob?.cancel()
        sample = voice to SampleState.LOADING
        sampleJob = scope.launch {
            try {
                val text = resources.getString(R.string.voice_sample_text, voice.replaceFirstChar(Char::uppercase))
                val audio = samples.get(provider, voice, text)
                sample = voice to SampleState.PLAYING
                samples.play(audio, settings.speechVolume)
                sample = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("Sayova", "No voice sample for $voice: ${e.message}")
                sample = voice to SampleState.FAILED
                delay(SAMPLE_FAILED_MS)
                sample = null
            }
        }
    }
    ListScreen { spec ->
        when (choice) {
            Choice.MODEL -> {
                item { Header(stringResource(R.string.settings_model), spec) }
                provider.models.forEach { model ->
                    item(key = model.id) {
                        RadioItem(stringOr(model.labelRes(), model.label), settings.modelId() == model.id, {
                            select { if (provider.id == ProviderId.GEMINI) it.copy(geminiModel = model.id) else it.copy(openAiModel = model.id) }
                        }, spec, stringOr(model.descriptionRes(), model.description))
                    }
                }
            }
            Choice.VOICE -> {
                item { Header(stringResource(R.string.settings_voice), spec) }
                item { Hint(stringResource(R.string.voice_hint), spec) }
                provider.voices.forEach { voice ->
                    item(key = voice.id) {
                        val state = sample?.takeIf { it.first == voice.id }?.second
                        RadioItem(
                            voice.id.replaceFirstChar(Char::uppercase),
                            settings.voiceId() == voice.id,
                            {
                                // Chosen at once, but the list stays open: the user may want to hear a few.
                                onUpdate { if (provider.id == ProviderId.GEMINI) it.copy(geminiVoice = voice.id) else it.copy(openAiVoice = voice.id) }
                                hear(voice.id)
                            },
                            spec,
                            when (state) {
                                SampleState.LOADING -> stringResource(R.string.voice_sample_loading)
                                SampleState.PLAYING -> stringResource(R.string.voice_sample_playing)
                                SampleState.FAILED -> stringResource(R.string.voice_sample_failed)
                                null -> stringOr(voice.descriptionRes(), voice.description)
                            },
                            icon = if (state == SampleState.PLAYING) R.drawable.ic_volume_up else null,
                        )
                    }
                }
            }
            Choice.LANGUAGE -> {
                item { Header(stringResource(R.string.settings_language), spec) }
                item { Hint(stringResource(R.string.language_hint), spec) }
                AnswerLanguage.entries.forEach { language ->
                    item(key = language.name) {
                        RadioItem(stringOr(language.labelRes(), language.label), settings.language == language, { select { it.copy(language = language) } }, spec)
                    }
                }
            }
            Choice.APP_LANGUAGE -> {
                item { Header(stringResource(R.string.settings_app_language), spec) }
                // Each language in its own name, so a user lost in a foreign one finds theirs.
                (listOf<String?>(null) + AppLanguages.TAGS).forEach { tag ->
                    item(key = tag ?: "system") {
                        RadioItem(
                            tag?.let(AppLanguages::name) ?: stringResource(R.string.app_language_system),
                            AppLanguages.current(context) == tag,
                            {
                                onDone()
                                AppLanguages.set(context, tag)
                            },
                            spec,
                        )
                    }
                }
            }
            Choice.PERSONA -> {
                item { Header(stringResource(R.string.settings_persona), spec) }
                Persona.entries.forEach { persona ->
                    item(key = persona.name) {
                        val secondary = if (persona == Persona.CUSTOM && settings.customPersona.isNotBlank()) settings.customPersona else stringResource(persona.descriptionRes())
                        RadioItem(stringResource(persona.labelRes()), settings.persona == persona, {
                            if (persona == Persona.CUSTOM) customInput() else select { it.copy(persona = persona) }
                        }, spec, secondary)
                    }
                }
            }
            Choice.BARGE_IN -> {
                item { Header(stringResource(R.string.settings_barge_in), spec) }
                item { Hint(stringResource(R.string.barge_in_hint), spec) }
                BargeIn.entries.forEach { option ->
                    item(key = option.name) {
                        RadioItem(stringResource(option.labelRes()), settings.bargeIn == option, { select { it.copy(bargeIn = option) } }, spec, stringResource(option.descriptionRes()))
                    }
                }
            }
            Choice.AUTO_END -> {
                item { Header(stringResource(R.string.settings_auto_end), spec) }
                SayovaSettings.AUTO_END_OPTIONS.forEach { seconds ->
                    item(key = seconds) {
                        RadioItem(autoEndLabel(seconds), settings.autoEndSeconds == seconds, { select { it.copy(autoEndSeconds = seconds) } }, spec)
                    }
                }
            }
            Choice.HISTORY -> {
                item { Header(stringResource(R.string.settings_history), spec) }
                item { Hint(stringResource(R.string.history_hint), spec) }
                SayovaSettings.HISTORY_OPTIONS.forEach { days ->
                    item(key = days) {
                        RadioItem(historyLabel(days), settings.historyDays == days, { select { it.copy(historyDays = days) } }, spec)
                    }
                }
            }
            Choice.BUDGET -> {
                item { Header(stringResource(R.string.settings_budget), spec) }
                SayovaSettings.BUDGET_OPTIONS.forEach { usd ->
                    item(key = usd) {
                        RadioItem(budgetLabel(usd), settings.budgetUsd == usd, { select { it.copy(budgetUsd = usd) } }, spec)
                    }
                }
                item(key = "save") {
                    SwitchItem(
                        stringResource(R.string.settings_budget_save), settings.budgetSave, { v -> onUpdate { it.copy(budgetSave = v) } }, spec,
                        stringResource(R.string.settings_budget_save_note, nearPercent()), R.drawable.ic_bolt,
                        enabled = settings.budgetUsd > 0,
                    )
                }
            }
            Choice.INTERPRET_FIRST, Choice.INTERPRET_SECOND -> {
                val isFirst = choice == Choice.INTERPRET_FIRST
                val (first, second) = settings.interpretPair(deviceLanguage)
                val current = if (isFirst) first else second
                val other = if (isFirst) second else first
                item { Header(stringResource(if (isFirst) R.string.interpreter_first else R.string.interpreter_second), spec) }
                InterpretLanguage.entries.forEach { language ->
                    item(key = language.code) {
                        RadioItem(language.ownName, language == current, {
                            // The other side's language changes sides instead of leaving both the same.
                            val rest = if (language == other) current else other
                            select {
                                if (isFirst) it.copy(interpretFirst = language.code, interpretSecond = rest.code)
                                else it.copy(interpretFirst = rest.code, interpretSecond = language.code)
                            }
                        }, spec)
                    }
                }
            }
        }
    }
}

/** Which provider is used, its key, and switching or removing it. */
@Composable
fun ProviderScreen(
    settings: SayovaSettings,
    connected: Set<ProviderId>,
    keyHint: (ProviderId) -> String?,
    onUse: (ProviderId) -> Unit,
    onConnect: (ProviderId) -> Unit,
    onRemove: (ProviderId) -> Unit,
) {
    var removing by remember { mutableStateOf<ProviderId?>(null) }
    ListScreen { spec ->
        item { Header(stringResource(R.string.provider_title), spec) }
        ProviderCatalog.all.forEach { provider ->
            item(key = "use-${provider.id}") {
                RadioItem(
                    provider.displayName,
                    settings.provider == provider.id,
                    { if (provider.id in connected) onUse(provider.id) else onConnect(provider.id) },
                    spec,
                    keyHint(provider.id)?.let { stringResource(R.string.provider_key_hint, it) } ?: stringResource(R.string.provider_not_connected),
                )
            }
        }
        item { SubHeader(stringResource(R.string.provider_keys), spec) }
        ProviderCatalog.all.forEach { provider ->
            item(key = "connect-${provider.id}") {
                MenuButton(
                    label = stringResource(if (provider.id in connected) R.string.provider_replace_key else R.string.provider_connect, provider.displayName),
                    icon = R.drawable.ic_qr_code_2,
                    spec = spec,
                    onClick = { onConnect(provider.id) },
                )
            }
            if (provider.id in connected) {
                item(key = "remove-${provider.id}") {
                    MenuButton(
                        label = stringResource(R.string.provider_remove_key, provider.displayName),
                        icon = R.drawable.ic_delete,
                        spec = spec,
                        onClick = { removing = provider.id },
                    )
                }
            }
        }
        item { Hint(stringResource(R.string.provider_keys_note), spec) }
    }
    ConfirmDialog(
        visible = removing != null,
        title = stringResource(R.string.provider_remove_confirm),
        onConfirm = {
            removing?.let(onRemove)
            removing = null
        },
        onDismiss = { removing = null },
    )
}

@Composable
private fun autoEndLabel(seconds: Int): String =
    if (seconds == 0) stringResource(R.string.auto_end_never) else pluralStringResource(R.plurals.auto_end_seconds, seconds, seconds)

@Composable
private fun budgetLabel(usd: Int): String = if (usd == 0) stringResource(R.string.budget_off) else Budget.format(usd)

/** Where the budget's warning starts, in the watch's way of writing a percentage. */
@Composable
private fun nearPercent(): String = Budget.percent(Budget.NEAR_SHARE, LocalConfiguration.current.locales[0])

@Composable
private fun historyLabel(days: Int): String = when {
    days == 0 -> stringResource(R.string.history_keep_off)
    days < 0 -> stringResource(R.string.history_keep_forever)
    else -> pluralStringResource(R.plurals.history_keep_days, days, days)
}
