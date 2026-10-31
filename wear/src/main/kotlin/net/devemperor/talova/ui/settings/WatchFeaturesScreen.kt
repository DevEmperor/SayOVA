package net.devemperor.talova.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import net.devemperor.talova.Diagnostics
import net.devemperor.talova.R
import net.devemperor.talova.core.tools.WatchFeature
import net.devemperor.talova.data.TalovaSettings
import net.devemperor.talova.features.Access
import net.devemperor.talova.features.NotificationReader
import net.devemperor.talova.features.WatchFeatures
import net.devemperor.talova.ui.components.Header
import net.devemperor.talova.ui.components.Hint
import net.devemperor.talova.ui.components.ListScreen
import net.devemperor.talova.ui.components.MenuButton
import net.devemperor.talova.ui.components.SwitchItem
import net.devemperor.talova.ui.iconRes
import net.devemperor.talova.ui.labelRes

/**
 * The watch's own sensors and data, one switch each. A feature that is on is offered to the model and
 * asks for its permission the first time it is needed; switching it on here asks at once, and "Allow
 * now" catches up on all that still wait, also those refused before. Switching one off also hands its
 * permission back.
 *
 * Android shows its dialog at most twice per permission; after the second "no" a request comes back
 * without one. Only then does the button lead to the watch's settings, where the permission can still
 * be given.
 *
 * [onContinue]: shown as a step of the introduction, with a button to go on (allowing now is optional:
 * a feature still asks when first used).
 */
@Composable
fun WatchFeaturesScreen(settings: TalovaSettings, onUpdate: Update, onContinue: (() -> Unit)? = null) {
    val context = LocalContext.current
    val activity = LocalActivity.current ?: return
    // Android does not report permission changes; read them again whenever the screen comes back
    // (from the permission dialog or the system settings).
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumed++
        onPauseOrDispose { }
    }
    val access = remember(resumed, settings.featuresAsked) {
        WatchFeature.entries.associateWith { WatchFeatures.access(context, it, it in settings.featuresAsked) }
    }
    val available = remember(resumed) { WatchFeature.entries.filter { WatchFeatures.available(context, it) }.toSet() }
    var asking by remember { mutableStateOf(emptyList<WatchFeature>()) }
    var switchingOn by remember { mutableStateOf<WatchFeature?>(null) }
    // Refused for good: only the settings can allow these now. Learned from asking, so each visit
    // tries the dialog first.
    var settingsOnly by remember { mutableStateOf(emptySet<WatchFeature>()) }
    val openSystemSettings = {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
    }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        val asked = asking
        val refused = switchingOn?.takeUnless { WatchFeatures.granted(context, it) }
        onUpdate { it.copy(featuresAsked = it.featuresAsked + asked, featuresOff = it.featuresOff + listOfNotNull(refused)) }
        // Android sets its rationale flag at the first "no" and clears it at the second, from when on it
        // shows no dialog. Neither the time to the answer nor whether this screen was covered tells that.
        val locked = asked.filter { feature ->
            !WatchFeatures.granted(context, feature) && WatchFeatures.permissions(feature).none(activity::shouldShowRequestPermissionRationale)
        }
        settingsOnly = settingsOnly - asked.toSet() + locked
        if (Diagnostics.enabled) Log.d("TalovaEvent", "permission answer $results, only in the settings now: $locked")
        asking = emptyList()
        switchingOn = null
        resumed++
    }
    // Notification access is a switch in the system settings, which Wear OS itself does not offer
    // (only adb grants it there); some watches have one.
    val notificationAccess = remember { NotificationReader.settingsIntent(context) }
    fun ask(features: List<WatchFeature>) {
        asking = features
        request.launch(features.flatMap(WatchFeatures::permissions).toTypedArray())
    }

    ListScreen { spec ->
        item { Header(stringResource(R.string.features_title), spec) }
        item { Hint(stringResource(R.string.features_examples), spec) }
        WatchFeature.entries.forEach { feature ->
            item(key = feature.name) {
                // A feature this watch cannot give stays off, its switch greyed out.
                val usable = feature in available
                val on = usable && feature in settings.features
                val state = when {
                    !usable -> stringResource(R.string.feature_unavailable)
                    !on -> null
                    access[feature] == Access.SYSTEM_SETTING -> stringResource(R.string.feature_system_setting)
                    // Music, alarm, apps, weather, barometer, the phone: nothing to allow.
                    WatchFeatures.permissions(feature).isEmpty() && feature != WatchFeature.NOTIFICATIONS -> null
                    access[feature] == Access.ALLOWED -> stringResource(R.string.feature_allowed)
                    access[feature] == Access.DENIED -> stringResource(R.string.feature_not_allowed)
                    else -> stringResource(R.string.feature_asks_first)
                }
                SwitchItem(
                    label = stringResource(feature.labelRes()),
                    checked = on,
                    onCheckedChange = { checked ->
                        if (checked) {
                            onUpdate { it.copy(featuresOff = it.featuresOff - feature) }
                            when {
                                access[feature] == Access.ALLOWED -> Unit
                                access[feature] == Access.SYSTEM_SETTING -> notificationAccess?.let(context::startActivity)
                                feature in settingsOnly -> openSystemSettings()
                                else -> {
                                    switchingOn = feature
                                    ask(listOf(feature))
                                }
                            }
                        } else {
                            onUpdate { it.copy(featuresOff = it.featuresOff + feature) }
                            // Given back when Talova's process ends; Android allows no sooner.
                            val permissions = WatchFeatures.permissions(feature)
                            if (Build.VERSION.SDK_INT >= 33 && permissions.isNotEmpty()) context.revokeSelfPermissionsOnKill(permissions)
                        }
                    },
                    spec = spec,
                    secondaryLabel = state,
                    icon = feature.iconRes(),
                    enabled = usable,
                )
            }
        }
        val waiting = settings.features.filter { it in available && access[it] != Access.ALLOWED && access[it] != Access.SYSTEM_SETTING }
        if (waiting.isNotEmpty()) {
            item {
                val askable = waiting.filter { it !in settingsOnly }
                MenuButton(
                    label = stringResource(if (askable.isEmpty()) R.string.features_open_settings else R.string.features_allow_now),
                    spec = spec,
                    onClick = { if (askable.isEmpty()) openSystemSettings() else ask(askable) },
                    icon = R.drawable.ic_check,
                    highlighted = true,
                )
            }
        }
        if (onContinue != null) {
            item {
                MenuButton(
                    label = stringResource(R.string.action_continue),
                    spec = spec,
                    onClick = onContinue,
                    icon = R.drawable.ic_arrow_forward,
                    // One highlighted button at a time: allowing first, while anything still waits.
                    highlighted = waiting.isEmpty(),
                )
            }
        }
        item { Hint(stringResource(R.string.features_hint), spec) }
        item { Hint(stringResource(R.string.features_privacy), spec) }
    }
}
