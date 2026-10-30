package net.devemperor.parley.ui.interpreter

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import net.devemperor.parley.R
import net.devemperor.parley.core.prompt.InterpretLanguage
import net.devemperor.parley.ui.components.Header
import net.devemperor.parley.ui.components.Hint
import net.devemperor.parley.ui.components.ListScreen
import net.devemperor.parley.ui.components.MenuButton
import net.devemperor.parley.ui.settings.Choice

/**
 * The interpreter's start: the two languages, each a tap away from its list, and the start on top.
 * The pair is remembered, so the next time it is one tap from the home screen and one more to go.
 */
@Composable
fun InterpreterScreen(
    pair: Pair<InterpretLanguage, InterpretLanguage>,
    onStart: () -> Unit,
    onPick: (Choice) -> Unit,
) {
    ListScreen { spec ->
        item { Header(stringResource(R.string.interpreter_title), spec) }
        item {
            MenuButton(
                label = stringResource(R.string.interpreter_start),
                secondaryLabel = "${pair.first.ownName} ⇄ ${pair.second.ownName}",
                icon = R.drawable.ic_mic,
                highlighted = true,
                spec = spec,
                onClick = onStart,
            )
        }
        item {
            MenuButton(stringResource(R.string.interpreter_first), spec, { onPick(Choice.INTERPRET_FIRST) }, pair.first.ownName, R.drawable.ic_translate)
        }
        item {
            MenuButton(stringResource(R.string.interpreter_second), spec, { onPick(Choice.INTERPRET_SECOND) }, pair.second.ownName, R.drawable.ic_translate)
        }
        item { Hint(stringResource(R.string.interpreter_hint), spec) }
    }
}
