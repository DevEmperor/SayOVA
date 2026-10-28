package net.devemperor.parley.ui.memory

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import net.devemperor.parley.R
import net.devemperor.parley.core.memory.Fact
import net.devemperor.parley.core.memory.Memory
import net.devemperor.parley.ui.components.Header
import net.devemperor.parley.ui.components.Hint
import net.devemperor.parley.ui.components.ListScreen
import net.devemperor.parley.ui.components.MenuButton
import net.devemperor.parley.ui.components.SubHeader
import net.devemperor.parley.ui.components.SwitchItem

/** What Parley remembers about the user: turned on or off, read, and forgotten one by one or all. */
@Composable
fun MemoryScreen(
    enabled: Boolean,
    facts: List<Fact>,
    onEnable: (Boolean) -> Unit,
    onForget: (Fact) -> Unit,
    onForgetAll: () -> Unit,
) {
    var forgetting by remember { mutableStateOf<Fact?>(null) }
    var forgettingAll by remember { mutableStateOf(false) }
    ListScreen { spec ->
        item { Header(stringResource(R.string.memory_title), spec) }
        item {
            SwitchItem(
                stringResource(R.string.memory_use), enabled, onEnable, spec,
                stringResource(R.string.memory_use_note), R.drawable.ic_psychology,
            )
        }
        if (facts.isEmpty()) {
            item { Hint(stringResource(R.string.memory_empty), spec) }
        } else {
            item { SubHeader(stringResource(R.string.memory_count, facts.size, Memory.LIMIT), spec) }
            facts.forEach { fact ->
                item(key = fact.number) { FactItem(fact, { forgetting = fact }, spec) }
            }
            item { MenuButton(stringResource(R.string.memory_forget_all), spec, { forgettingAll = true }, icon = R.drawable.ic_delete) }
        }
        item { Hint(stringResource(R.string.memory_privacy), spec) }
    }
    AlertDialog(
        visible = forgetting != null,
        onDismissRequest = { forgetting = null },
        icon = { Icon(painterResource(R.drawable.ic_delete), contentDescription = null) },
        title = { Text(stringResource(R.string.memory_forget_confirm)) },
        text = { forgetting?.let { Text(it.text, textAlign = TextAlign.Center, maxLines = 4, overflow = TextOverflow.Ellipsis) } },
        confirmButton = {
            AlertDialogDefaults.ConfirmButton(onClick = {
                forgetting?.let(onForget)
                forgetting = null
            })
        },
    )
    AlertDialog(
        visible = forgettingAll,
        onDismissRequest = { forgettingAll = false },
        icon = { Icon(painterResource(R.drawable.ic_delete), contentDescription = null) },
        title = { Text(stringResource(R.string.memory_forget_all_confirm)) },
        confirmButton = {
            AlertDialogDefaults.ConfirmButton(onClick = {
                forgettingAll = false
                onForgetAll()
            })
        },
    )
}

/** One fact, in full: up to 150 characters, which needs more lines than a menu button gives. */
@Composable
private fun TransformingLazyColumnItemScope.FactItem(fact: Fact, onClick: () -> Unit, spec: TransformationSpec) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
        transformation = SurfaceTransformation(spec),
        colors = ButtonDefaults.filledTonalButtonColors(),
        label = { Text(fact.text, maxLines = 8, overflow = TextOverflow.Ellipsis) },
    )
}
