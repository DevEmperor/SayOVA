package net.devemperor.parley.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import net.devemperor.parley.R
import net.devemperor.parley.core.cost.CostMeter
import net.devemperor.parley.core.provider.ProviderId
import net.devemperor.parley.core.usage.Budget
import net.devemperor.parley.core.usage.BudgetLevel
import net.devemperor.parley.core.usage.Usage
import net.devemperor.parley.data.ParleySettings
import net.devemperor.parley.parley
import net.devemperor.parley.session.ConversationUiState
import net.devemperor.parley.session.SessionHub
import net.devemperor.parley.ui.about.AboutScreen
import net.devemperor.parley.ui.about.LicensesScreen
import net.devemperor.parley.ui.components.ConfirmDialog
import net.devemperor.parley.ui.components.OrbHandoff
import net.devemperor.parley.ui.conversation.ConversationScreen
import net.devemperor.parley.ui.history.ConversationDetailScreen
import net.devemperor.parley.ui.history.HistoryScreen
import net.devemperor.parley.ui.history.ReportScreen
import net.devemperor.parley.ui.home.HomeScreen
import net.devemperor.parley.ui.memory.MemoryScreen
import net.devemperor.parley.ui.settings.Choice
import net.devemperor.parley.ui.settings.ChoiceScreen
import net.devemperor.parley.ui.settings.ProviderScreen
import net.devemperor.parley.ui.settings.SettingsScreen
import net.devemperor.parley.ui.settings.WatchFeaturesScreen
import net.devemperor.parley.ui.setup.PairingScreen
import net.devemperor.parley.ui.setup.PairingViewModel
import net.devemperor.parley.ui.setup.PermissionScreen
import net.devemperor.parley.ui.setup.ProviderChoiceScreen
import net.devemperor.parley.ui.setup.WelcomeScreen
import net.devemperor.parley.ui.setup.hasMicrophone
import net.devemperor.parley.ui.theme.ParleyTheme
import net.devemperor.parley.ui.usage.UsageScreen
import java.time.YearMonth

object Routes {
    const val HOME = "home"
    const val CONVERSATION = "conversation"
    const val WELCOME = "welcome"
    const val PERMISSION = "permission"
    const val CHOOSE_PROVIDER = "choose_provider"
    const val PAIR = "pair/{provider}"
    const val HISTORY = "history"
    const val DETAIL = "history/{id}"
    const val REPORT = "report/{id}"
    const val SETTINGS = "settings"
    const val PROVIDER = "provider"
    const val CHOICE = "choice/{choice}"
    const val ABOUT = "about"
    const val LICENSES = "licenses"
    const val USAGE = "usage"
    const val MEMORY = "memory"
    const val FEATURES = "features"

    fun pair(provider: ProviderId) = "pair/${provider.key}"
    fun detail(id: String) = "history/$id"
    fun report(id: String) = "report/$id"
    fun choice(choice: Choice) = "choice/${choice.name}"
}

/** What the app was opened for: from the launcher, or straight into a conversation (tile, complication). */
enum class Launch { OPEN, TALK, DEMO }

/** A conversation that waits for the user's yes, because this month's budget is spent (see Budget). */
private data class HeldStart(val demo: Boolean, val continueId: String?)

/** [relaunches]: the tile or complication tapped while Parley is already open. */
@Composable
fun ParleyUi(launch: Launch, initialRoutes: List<String>, relaunches: Flow<Launch>) {
    val context = LocalContext.current
    val app = context.parley
    val scope = rememberCoroutineScope()
    val settings by app.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val connected by app.keys.connected.collectAsStateWithLifecycle()
    val conversations by app.history.conversations.collectAsStateWithLifecycle()
    val usage by app.usage.days.collectAsStateWithLifecycle()
    val facts by app.memory.facts.collectAsStateWithLifecycle()
    val session by SessionHub.state.collectAsStateWithLifecycle()
    val navController = rememberSwipeDismissableNavController()

    ParleyTheme {
        AppScaffold {
            val current = settings ?: return@AppScaffold
            val ready = connected.isNotEmpty() && context.hasMicrophone()
            val update: ((ParleySettings) -> ParleySettings) -> Unit = { transform -> scope.launch { app.settings.update(transform) } }
            /** A conversation shows in place of the home screen (see startConversation). */
            var homeTalk by rememberSaveable { mutableStateOf(false) }
            var held by remember { mutableStateOf<HeldStart?>(null) }

            /** [anyway]: the user said yes although the month's budget is spent. */
            fun startConversation(demo: Boolean = false, continueId: String? = null, anyway: Boolean = false) {
                if (!context.hasMicrophone()) {
                    navController.navigate(Routes.PERMISSION)
                    return
                }
                // Settings and usage read now, not as this composition saw them: the tile's starts come
                // through an effect launched with the first one, and the usage straight from the store,
                // which may have loaded a frame before the screen saw it.
                val spent = Usage.month(app.usage.days.value, YearMonth.now()).costUsd
                if (!anyway && !SessionHub.running && Budget.level(spent, settings?.budgetUsd ?: 0) == BudgetLevel.REACHED) {
                    held = HeldStart(demo, continueId)
                    return
                }
                SessionHub.start(context, demo, continueId)
                when (navController.currentDestination?.route) {
                    // Started from the home screen, the conversation takes its place and the home orb
                    // grows into the conversation's: a page of its own would come in with Wear's
                    // round reveal, which cut the growing orb off.
                    Routes.HOME -> {
                        OrbHandoff.launch()
                        homeTalk = true
                    }
                    Routes.CONVERSATION -> Unit
                    else -> navController.navigate(Routes.CONVERSATION)
                }
            }

            /** The conversation, on its own page or in place of the home screen ([onHome]); [onDone] leaves it. */
            @Composable
            fun Conversation(state: ConversationUiState, s: ParleySettings, onHome: Boolean, onDone: () -> Unit) {
                ConversationScreen(
                    state = state,
                    showTranscript = s.showTranscript,
                    onHome = onHome,
                    onToggleTranscript = { update { it.copy(showTranscript = !it.showTranscript) } },
                    onToggleSound = {
                        val off = !state.soundOff
                        SessionHub.setSoundOff(off)
                        update { it.copy(soundOff = off) }
                    },
                    onTapOrb = SessionHub::tapOrb,
                    onEnd = { SessionHub.end(context) },
                    onDone = {
                        SessionHub.dismiss()
                        onDone()
                    },
                    onRetry = {
                        SessionHub.dismiss()
                        startConversation()
                    },
                    onFixProvider = {
                        SessionHub.dismiss()
                        homeTalk = false
                        navController.navigate(Routes.pair(current.provider))
                    },
                )
            }

            // Decided once: recomputing it when a key arrives would rebuild the graph mid-setup and
            // skip the "Connected" screen.
            val start = remember {
                when {
                    launch == Launch.DEMO -> Routes.HOME
                    SessionHub.running -> Routes.CONVERSATION
                    !ready -> Routes.WELCOME
                    else -> Routes.HOME
                }
            }

            SwipeDismissableNavHost(navController = navController, startDestination = start) {
                composable(Routes.HOME) {
                    val s by app.settings.settings.collectAsStateWithLifecycle(initialValue = current)
                    // Swiping back during a conversation shows the home screen again; it keeps running.
                    BackHandler(enabled = homeTalk) { homeTalk = false }
                    AnimatedContent(
                        targetState = homeTalk && session != null,
                        transitionSpec = {
                            // The ended conversation's orb has landed on the home orb: the conversation
                            // goes only once the home screen has come in, so the orb never dims.
                            val landed = !targetState && OrbHandoff.takeLanded()
                            fadeIn(tween(HOME_FADE_MS)) togetherWith fadeOut(tween(HOME_FADE_MS, delayMillis = if (landed) HOME_FADE_MS else 0))
                        },
                        label = "home",
                    ) { talking ->
                        val state = session
                        if (talking && state != null) {
                            Conversation(state, s, onHome = true, onDone = { homeTalk = false })
                        } else {
                            // A conversation is likely to start from here: wake the Wi-Fi already.
                            LifecycleResumeEffect(Unit) {
                                app.network.prewarm()
                                onPauseOrDispose { }
                            }
                            HomeScreen(
                                settings = s,
                                monthUsd = Usage.month(usage, YearMonth.now()).costUsd,
                                onUsage = whenResumed { navController.navigate(Routes.USAGE) },
                                onTalk = whenResumed { startConversation() },
                                onHistory = whenResumed { navController.navigate(Routes.HISTORY) },
                                onSettings = whenResumed { navController.navigate(Routes.SETTINGS) },
                            )
                        }
                    }
                }
                composable(Routes.CONVERSATION) {
                    val s by app.settings.settings.collectAsStateWithLifecycle(initialValue = current)
                    val state = session
                    if (state == null) {
                        LaunchedEffect(Unit) { if (!navController.popBackStack(Routes.HOME, inclusive = false)) navController.navigateHome() }
                    } else {
                        Conversation(state, s, onHome = false, onDone = {
                            if (!navController.popBackStack(Routes.HOME, inclusive = false)) navController.navigateHome()
                        })
                    }
                }
                composable(Routes.WELCOME) {
                    WelcomeScreen(onStart = whenResumed {
                        navController.navigate(if (context.hasMicrophone()) Routes.CHOOSE_PROVIDER else Routes.PERMISSION)
                    })
                }
                composable(Routes.PERMISSION) {
                    PermissionScreen(onGranted = {
                        if (navController.currentDestination?.route == Routes.PERMISSION) {
                            navController.popBackStack()
                            if (connected.isEmpty()) navController.navigate(Routes.CHOOSE_PROVIDER)
                        }
                    })
                }
                composable(Routes.CHOOSE_PROVIDER) {
                    ProviderChoiceScreen(connected, onChoose = whenResumed { id: ProviderId -> navController.navigate(Routes.pair(id)) })
                }
                composable(Routes.PAIR) { entry ->
                    val provider = ProviderId.fromKey(entry.arguments?.getString("provider")) ?: ProviderId.GEMINI
                    val viewModel: PairingViewModel = viewModel()
                    PairingScreen(viewModel, provider, onDone = whenResumed { navController.navigateHome() })
                }
                composable(Routes.HISTORY) {
                    HistoryScreen(
                        conversations = conversations,
                        historyOff = current.historyDays == 0,
                        showCost = current.showCost,
                        onOpen = whenResumed { id: String -> navController.navigate(Routes.detail(id)) },
                        onDelete = { ids -> scope.launch { ids.forEach { app.history.delete(it) } } },
                    )
                }
                composable(Routes.DETAIL) { entry ->
                    val conversation = entry.arguments?.getString("id")?.let { id -> conversations.firstOrNull { it.id == id } }
                    if (conversation != null) {
                        ConversationDetailScreen(
                            conversation,
                            onContinue = whenResumed { startConversation(continueId = conversation.id) },
                            onReport = whenResumed { navController.navigate(Routes.report(conversation.id)) },
                            onDelete = {
                                scope.launch { app.history.delete(conversation.id) }
                                navController.popBackStack()
                            },
                        )
                    }
                }
                composable(Routes.REPORT) { entry ->
                    val conversation = entry.arguments?.getString("id")?.let { id -> conversations.firstOrNull { it.id == id } }
                    if (conversation != null) ReportScreen(conversation, onSent = { navController.popBackStack() })
                }
                composable(Routes.SETTINGS) {
                    val s by app.settings.settings.collectAsStateWithLifecycle(initialValue = current)
                    SettingsScreen(
                        settings = s,
                        connected = connected,
                        onUpdate = update,
                        onOpenProvider = whenResumed { navController.navigate(Routes.PROVIDER) },
                        onOpenChoice = whenResumed { choice: Choice -> navController.navigate(Routes.choice(choice)) },
                        onDeleteHistory = { scope.launch { app.history.deleteAll() } },
                        onOpenAbout = whenResumed { navController.navigate(Routes.ABOUT) },
                        monthUsage = Usage.month(usage, YearMonth.now()),
                        onOpenUsage = whenResumed { navController.navigate(Routes.USAGE) },
                        memoryFacts = facts.size,
                        onOpenMemory = whenResumed { navController.navigate(Routes.MEMORY) },
                        onOpenFeatures = whenResumed { navController.navigate(Routes.FEATURES) },
                    )
                }
                composable(Routes.PROVIDER) {
                    val s by app.settings.settings.collectAsStateWithLifecycle(initialValue = current)
                    ProviderScreen(
                        settings = s,
                        connected = connected,
                        keyHint = app.keys::hint,
                        // Like every other choice from a list, back at once (see the choice screens).
                        onUse = { id ->
                            update { it.copy(provider = id) }
                            if (navController.currentDestination?.route == Routes.PROVIDER) navController.popBackStack()
                        },
                        onConnect = whenResumed { id: ProviderId -> navController.navigate(Routes.pair(id)) },
                        onRemove = { id ->
                            app.keys.remove(id)
                            val other = ProviderId.entries.firstOrNull { it != id && it in app.keys.connected.value }
                            if (other != null) update { it.copy(provider = other) }
                        },
                    )
                }
                composable(Routes.CHOICE) { entry ->
                    val choice = entry.arguments?.getString("choice")?.let { name -> Choice.entries.firstOrNull { it.name == name } }
                    if (choice != null) {
                        val s by app.settings.settings.collectAsStateWithLifecycle(initialValue = current)
                        // Back after a choice even while the list is still sliding in: whenResumed dropped a
                        // quick tap's way back (the choice itself was kept). Popping only while the list
                        // shows guards against a double tap instead.
                        ChoiceScreen(choice, s, update, onDone = {
                            if (navController.currentDestination?.route == Routes.CHOICE) navController.popBackStack()
                        })
                    }
                }
                composable(Routes.ABOUT) { AboutScreen(onLicenses = whenResumed { navController.navigate(Routes.LICENSES) }) }
                composable(Routes.LICENSES) { LicensesScreen() }
                composable(Routes.USAGE) { UsageScreen(usage, current.budgetUsd) }
                composable(Routes.FEATURES) {
                    val s by app.settings.settings.collectAsStateWithLifecycle(initialValue = current)
                    WatchFeaturesScreen(s, update)
                }
                composable(Routes.MEMORY) {
                    val s by app.settings.settings.collectAsStateWithLifecycle(initialValue = current)
                    MemoryScreen(
                        enabled = s.memory,
                        facts = facts,
                        onEnable = { on -> update { it.copy(memory = on) } },
                        onForget = { fact -> scope.launch { app.memory.forget(fact.number) } },
                        onForgetAll = { scope.launch { app.memory.forgetAll() } },
                    )
                }
            }

            ConfirmDialog(
                visible = held != null,
                title = stringResource(R.string.budget_reached_title),
                text = stringResource(
                    R.string.budget_reached_text,
                    CostMeter.format(Usage.month(usage, YearMonth.now()).costUsd),
                    Budget.format(current.budgetUsd),
                ),
                icon = R.drawable.ic_savings,
                onConfirm = {
                    val start = held
                    held = null
                    if (start != null) startConversation(start.demo, start.continueId, anyway = true)
                },
                onDismiss = { held = null },
            )

            LaunchedEffect(Unit) {
                // Opened to talk at once: checked against the month on file, not an empty one still loading.
                app.usage.awaitLoaded()
                when (launch) {
                    Launch.TALK -> if (ready) startConversation()
                    Launch.DEMO -> startConversation(demo = true)
                    Launch.OPEN -> if (ready && current.startOnLaunch && !SessionHub.running) startConversation()
                }
                initialRoutes.forEach { runCatching { navController.navigate(it) } }
            }
            LaunchedEffect(Unit) {
                relaunches.collect {
                    if (connected.isNotEmpty() && context.hasMicrophone()) startConversation()
                }
            }
            // A conversation started without startConversation's navigation brings its screen up too; on
            // the home screen it takes the home screen's place.
            LaunchedEffect(session?.active) {
                if (session?.active != true) return@LaunchedEffect
                when (navController.currentDestination?.route) {
                    Routes.CONVERSATION -> Unit
                    Routes.HOME -> homeTalk = true
                    else -> navController.navigate(Routes.CONVERSATION)
                }
            }
        }
    }
}

private const val HOME_FADE_MS = 250

private fun NavHostController.navigateHome() {
    navigate(Routes.HOME) { popUpTo(graph.id) { inclusive = true } }
}

/** Runs [block] only while the current destination is resumed, so a double tap cannot navigate twice. */
@Composable
private fun whenResumed(block: () -> Unit): () -> Unit {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return { if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) block() }
}

@Composable
private fun <T> whenResumed(block: (T) -> Unit): (T) -> Unit {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return { value -> if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) block(value) }
}
