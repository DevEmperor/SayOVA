package net.devemperor.parley.ui.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OpenOnPhoneDialog
import androidx.wear.compose.material3.OpenOnPhoneDialogDefaults
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.curvedText
import androidx.wear.compose.material3.lazy.transformedHeight
import net.devemperor.parley.BuildConfig
import net.devemperor.parley.R
import net.devemperor.parley.ui.components.Header
import net.devemperor.parley.ui.components.Hint
import net.devemperor.parley.ui.components.ListScreen
import net.devemperor.parley.ui.components.MenuButton
import net.devemperor.parley.ui.components.Orb
import net.devemperor.parley.ui.components.openOnPhone

private const val REPOSITORY = "https://github.com/DevEmperor/Parley"
private const val PRIVACY = "$REPOSITORY/blob/main/PRIVACY_POLICY.md"

@Composable
fun AboutScreen(onLicenses: () -> Unit) {
    val context = LocalContext.current
    var opened by remember { mutableStateOf(false) }
    val open: (String) -> Unit = { url -> opened = openOnPhone(context, url) }
    val continueOnPhone = stringResource(R.string.about_continue_on_phone)
    ListScreen { spec ->
        item {
            Column(
                Modifier.fillMaxWidth().transformedHeight(this, spec),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Orb(phase = null, level = 0f, modifier = Modifier.size(56.dp))
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item { Hint(stringResource(R.string.about_privacy_summary), spec) }
        item { MenuButton(stringResource(R.string.about_privacy), spec, { open(PRIVACY) }, stringResource(R.string.about_on_phone), R.drawable.ic_description) }
        item { MenuButton(stringResource(R.string.about_source), spec, { open(REPOSITORY) }, stringResource(R.string.about_license), R.drawable.ic_gavel) }
        item { MenuButton(stringResource(R.string.about_licenses), spec, onLicenses, icon = R.drawable.ic_info) }
        item { MenuButton(stringResource(R.string.about_contact), spec, { open("mailto:contact@devemperor.net") }, "contact@devemperor.net", R.drawable.ic_mail) }
    }
    OpenOnPhoneDialog(
        visible = opened,
        onDismissRequest = { opened = false },
        curvedText = { curvedText(continueOnPhone) },
        content = { OpenOnPhoneDialogDefaults.Icon() },
    )
}

@Composable
fun LicensesScreen() {
    val licenses = listOf(
        "Jetpack Compose, AndroidX, Wear OS libraries" to "Apache License 2.0 · Google",
        "Kotlin, kotlinx.coroutines, kotlinx.serialization" to "Apache License 2.0 · JetBrains",
        "OkHttp, Okio" to "Apache License 2.0 · Square",
        "Tink" to "Apache License 2.0 · Google",
        "QR Code generator" to "MIT License · Project Nayuki",
        "Material Symbols" to "Apache License 2.0 · Google",
    )
    ListScreen { spec ->
        item { Header(stringResource(R.string.about_licenses), spec) }
        licenses.forEach { (name, license) ->
            item(key = name) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).transformedHeight(this, spec)) {
                    Text(name, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    Text(
                        license,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
