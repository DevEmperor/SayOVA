package net.devemperor.parley.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.Text
import net.devemperor.parley.BuildConfig
import net.devemperor.parley.R
import net.devemperor.parley.core.cost.CostMeter
import net.devemperor.parley.core.prompt.AnswerLanguage
import net.devemperor.parley.core.prompt.Persona
import net.devemperor.parley.core.provider.ProviderCatalog
import net.devemperor.parley.core.provider.ProviderId
import net.devemperor.parley.core.usage.Usage
import net.devemperor.parley.core.usage.UsageTotal
import net.devemperor.parley.data.BargeIn
import net.devemperor.parley.data.ParleySettings
import net.devemperor.parley.ui.components.Header
import net.devemperor.parley.ui.components.Hint
import net.devemperor.parley.ui.components.ListScreen
import net.devemperor.parley.ui.components.MenuButton
import net.devemperor.parley.ui.components.RadioItem
import net.devemperor.parley.ui.components.SubHeader
import net.devemperor.parley.ui.components.SwitchItem
import net.devemperor.parley.ui.components.rememberTextInput
import net.devemperor.parley.ui.descriptionRes
import net.devemperor.parley.ui.labelRes
import net.devemperor.parley.ui.stringOr

typealias Update = ((ParleySettings) -> ParleySettings) -> Unit

/** Options that are a single choice from a list get one generic screen each. */
enum class Choice { MODEL, VOICE, LANGUAGE, PERSONA, BARGE_IN, AUTO_END, HISTORY }

@Composable
fun SettingsScreen(
    settings: ParleySettings,
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
                stringResource(R.string.settings_web_search_note), R.drawable.ic_travel_explore,
            )
        }
        item {
            val on = settings.features
            val summary = if (on.isEmpty()) stringResource(R.string.features_none) else on.map { stringResource(it.labelRes()) }.joinToString(", ")
            MenuButton(stringResource(R.string.features_title), spec, onOpenFeatures, summary, R.drawable.ic_watch)
        }
        item { MenuButton(stringResource(R.string.settings_barge_in), spec, { onOpenChoice(Choice.BARGE_IN) }, stringResource(settings.bargeIn.labelRes()), R.drawable.ic_front_hand) }
        item {
            SwitchItem(
                stringResource(R.string.settings_push_to_talk), settings.pushToTalk, { v -> onUpdate { it.copy(pushToTalk = v) } }, spec,
                stringResource(R.string.settings_push_to_talk_note), R.drawable.ic_touch_app,
            )
        }
        item { MenuButton(stringResource(R.string.settings_auto_end), spec, { onOpenChoice(Choice.AUTO_END) }, autoEndLabel(settings.autoEndSeconds), R.drawable.ic_timer) }
        item {
            SwitchItem(
                stringResource(R.string.settings_start_on_launch), settings.startOnLaunch, { v -> onUpdate { it.copy(startOnLaunch = v) } }, spec,
                stringResource(R.string.settings_start_on_launch_note), R.drawable.ic_mic,
            )
        }
        item { SwitchItem(stringResource(R.string.settings_haptics), settings.haptics, { v -> onUpdate { it.copy(haptics = v) } }, spec, icon = R.drawable.ic_vibration) }
        item {
            SwitchItem(
                stringResource(R.string.settings_show_cost), settings.showCost, { v -> onUpdate { it.copy(showCost = v) } }, spec,
                stringResource(R.string.settings_show_cost_note), R.drawable.ic_payments,
            )
        }
        item {
            MenuButton(
                stringResource(R.string.settings_usage), spec, onOpenUsage,
                stringResource(R.string.settings_usage_summary, CostMeter.format(monthUsage.costUsd), Usage.formatDuration(monthUsage.talkMs)),
                R.drawable.ic_bar_chart,
            )
        }

        item { SubHeader(stringResource(R.string.settings_privacy), spec) }
        item { MenuButton(stringResource(R.string.settings_history), spec, { onOpenChoice(Choice.HISTORY) }, historyLabel(settings.historyDays), R.drawable.ic_history) }
        item { MenuButton(stringResource(R.string.settings_delete_history), spec, { confirmDelete = true }, icon = R.drawable.ic_delete) }
        item { MenuButton(stringResource(R.string.settings_about), spec, onOpenAbout, "${stringResource(R.string.app_name)} ${BuildConfig.VERSION_NAME}", R.drawable.ic_info) }
    }
    AlertDialog(
        visible = confirmDelete,
        onDismissRequest = { confirmDelete = false },
        icon = { Icon(painterResource(R.drawable.ic_delete), contentDescription = null) },
        title = { Text(stringResource(R.string.settings_delete_history_confirm)) },
        confirmButton = {
            AlertDialogDefaults.ConfirmButton(onClick = {
                confirmDelete = false
                onDeleteHistory()
            })
        },
    )
}

@Composable
fun ChoiceScreen(choice: Choice, settings: ParleySettings, onUpdate: Update, onDone: () -> Unit) {
    val provider = settings.providerInfo
    val select: ((ParleySettings) -> ParleySettings) -> Unit = { transform ->
        onUpdate(transform)
        onDone()
    }
    val customInput = rememberTextInput(stringResource(R.string.persona_custom_label)) { text ->
        if (text != null) select { it.copy(persona = Persona.CUSTOM, customPersona = text) }
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
                provider.voices.forEach { voice ->
                    item(key = voice.id) {
                        RadioItem(voice.id.replaceFirstChar(Char::uppercase), settings.voiceId() == voice.id, {
                            select { if (provider.id == ProviderId.GEMINI) it.copy(geminiVoice = voice.id) else it.copy(openAiVoice = voice.id) }
                        }, spec, stringOr(voice.descriptionRes(), voice.description))
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
                ParleySettings.AUTO_END_OPTIONS.forEach { seconds ->
                    item(key = seconds) {
                        RadioItem(autoEndLabel(seconds), settings.autoEndSeconds == seconds, { select { it.copy(autoEndSeconds = seconds) } }, spec)
                    }
                }
            }
            Choice.HISTORY -> {
                item { Header(stringResource(R.string.settings_history), spec) }
                item { Hint(stringResource(R.string.history_hint), spec) }
                ParleySettings.HISTORY_OPTIONS.forEach { days ->
                    item(key = days) {
                        RadioItem(historyLabel(days), settings.historyDays == days, { select { it.copy(historyDays = days) } }, spec)
                    }
                }
            }
        }
    }
}

/** Which provider is used, its key, and switching or removing it. */
@Composable
fun ProviderScreen(
    settings: ParleySettings,
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
    AlertDialog(
        visible = removing != null,
        onDismissRequest = { removing = null },
        icon = { Icon(painterResource(R.drawable.ic_delete), contentDescription = null) },
        title = { Text(stringResource(R.string.provider_remove_confirm)) },
        confirmButton = {
            AlertDialogDefaults.ConfirmButton(onClick = {
                removing?.let(onRemove)
                removing = null
            })
        },
    )
}

@Composable
private fun autoEndLabel(seconds: Int): String =
    if (seconds == 0) stringResource(R.string.auto_end_never) else pluralStringResource(R.plurals.auto_end_seconds, seconds, seconds)

@Composable
private fun historyLabel(days: Int): String = when {
    days == 0 -> stringResource(R.string.history_keep_off)
    days < 0 -> stringResource(R.string.history_keep_forever)
    else -> pluralStringResource(R.plurals.history_keep_days, days, days)
}
