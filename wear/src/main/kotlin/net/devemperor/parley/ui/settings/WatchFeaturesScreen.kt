package net.devemperor.parley.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import net.devemperor.parley.R
import net.devemperor.parley.core.tools.WatchFeature
import net.devemperor.parley.data.ParleySettings
import net.devemperor.parley.features.Access
import net.devemperor.parley.features.WatchFeatures
import net.devemperor.parley.ui.components.Header
import net.devemperor.parley.ui.components.Hint
import net.devemperor.parley.ui.components.ListScreen
import net.devemperor.parley.ui.components.MenuButton
import net.devemperor.parley.ui.components.SwitchItem
import net.devemperor.parley.ui.iconRes
import net.devemperor.parley.ui.labelRes

/**
 * The watch's own sensors and data, one switch each. A feature that is on is offered to the model and
 * asks for its permission the first time it is needed; switching it on here asks at once, and "Allow
 * now" catches up on all that still wait. Switching one off also hands its permission back.
 */
@Composable
fun WatchFeaturesScreen(settings: ParleySettings, onUpdate: Update) {
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
        WatchFeature.entries.associateWith { WatchFeatures.access(activity, it, it in settings.featuresAsked) }
    }
    var asking by remember { mutableStateOf(emptyList<WatchFeature>()) }
    var switchingOn by remember { mutableStateOf<WatchFeature?>(null) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val asked = asking
        val refused = switchingOn?.takeUnless { WatchFeatures.granted(context, it) }
        onUpdate { it.copy(featuresAsked = it.featuresAsked + asked, featuresOff = it.featuresOff + listOfNotNull(refused)) }
        asking = emptyList()
        switchingOn = null
        resumed++
    }
    val openSystemSettings = {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
    }
    fun ask(features: List<WatchFeature>) {
        asking = features
        request.launch(features.flatMap(WatchFeatures::permissions).toTypedArray())
    }

    ListScreen { spec ->
        item { Header(stringResource(R.string.features_title), spec) }
        item { Hint(stringResource(R.string.features_examples), spec) }
        WatchFeature.entries.forEach { feature ->
            item(key = feature.name) {
                val on = feature in settings.features
                val state = when {
                    !on -> null
                    access[feature] == Access.ALLOWED -> stringResource(R.string.feature_allowed)
                    access[feature] == Access.BLOCKED -> stringResource(R.string.feature_blocked)
                    else -> stringResource(R.string.feature_asks_first)
                }
                SwitchItem(
                    label = stringResource(feature.labelRes()),
                    checked = on,
                    onCheckedChange = { checked ->
                        if (checked) {
                            onUpdate { it.copy(featuresOff = it.featuresOff - feature) }
                            when (access[feature]) {
                                Access.ALLOWED -> Unit
                                Access.BLOCKED -> openSystemSettings()
                                else -> {
                                    switchingOn = feature
                                    ask(listOf(feature))
                                }
                            }
                        } else {
                            onUpdate { it.copy(featuresOff = it.featuresOff + feature) }
                            // Given back when Parley's process ends; Android allows no sooner.
                            if (Build.VERSION.SDK_INT >= 33) context.revokeSelfPermissionsOnKill(WatchFeatures.permissions(feature))
                        }
                    },
                    spec = spec,
                    secondaryLabel = state,
                    icon = feature.iconRes(),
                )
            }
        }
        val waiting = settings.features.filter { access[it] != Access.ALLOWED }
        if (waiting.isNotEmpty()) {
            item {
                val askable = waiting.filter { access[it] == Access.ASKS_FIRST }
                MenuButton(
                    label = stringResource(if (askable.isEmpty()) R.string.features_open_settings else R.string.features_allow_now),
                    spec = spec,
                    onClick = { if (askable.isEmpty()) openSystemSettings() else ask(askable) },
                    icon = R.drawable.ic_check,
                    highlighted = true,
                )
            }
        }
        item { Hint(stringResource(R.string.features_hint), spec) }
        item { Hint(stringResource(R.string.features_privacy), spec) }
    }
}
