package net.devemperor.talova.ui.setup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.devemperor.talova.R
import net.devemperor.talova.demo.SampleConversation
import net.devemperor.talova.ui.conversation.ConversationScreen
import net.devemperor.talova.ui.tour.Spotlight
import net.devemperor.talova.ui.tour.TourStep
import net.devemperor.talova.ui.tour.TourTarget

/** The tour of a conversation's controls, once the sample's exchanges are over; the last step hangs up. */
private val TALK_TOUR = listOf(
    TourStep(TourTarget.TALK_ORB, R.string.tour_talk_orb),
    TourStep(TourTarget.TALK_TEXT, R.string.tour_talk_text),
    TourStep(TourTarget.TALK_SOUND, R.string.tour_talk_sound),
    TourStep(TourTarget.TALK_END, R.string.tour_talk_end),
)

/**
 * The introduction's sample conversation on the conversation screen, with the text shown, so both
 * sides can be read along. Its controls work as in a conversation, only for the sample; the orb shows
 * no reaction to a tap, there being nobody to interrupt for real. [onDone]: hung up, or through the
 * tour; [onBack]: swiped away before that.
 */
@Composable
fun SampleScreen(sample: SampleConversation, onDone: () -> Unit, onBack: () -> Unit) {
    val state by sample.state.collectAsStateWithLifecycle()
    val scriptDone by sample.scriptDone.collectAsStateWithLifecycle()
    var showText by remember { mutableStateOf(true) }
    // Left in any way (swiped back, the activity gone), the sample stops and gives the volume back.
    DisposableEffect(sample) { onDispose(sample::end) }
    BackHandler(enabled = state.active, onBack = onBack)
    Box {
        ConversationScreen(
            state = state,
            showTranscript = showText,
            onHome = true,
            onToggleTranscript = { showText = !showText },
            onToggleSound = { sample.setSoundOff(!state.soundOff) },
            onTapOrb = {},
            onEnd = sample::end,
            onDone = onDone,
            onRetry = {},
            onFixProvider = {},
            onStepVolume = sample::stepVolume,
        )
        if (scriptDone && state.active) Spotlight(TALK_TOUR, onDone = sample::end)
    }
}
