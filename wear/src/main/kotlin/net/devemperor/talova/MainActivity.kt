package net.devemperor.talova

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import net.devemperor.talova.complication.TalovaComplicationService
import net.devemperor.talova.demo.SampleVoices
import net.devemperor.talova.features.PermissionBroker
import net.devemperor.talova.features.StepTracking
import net.devemperor.talova.features.WatchFeatures
import net.devemperor.talova.session.SessionHub
import net.devemperor.talova.tile.TalovaTileService
import net.devemperor.talova.ui.Launch
import net.devemperor.talova.ui.TalovaUi

class MainActivity : ComponentActivity() {

    /** The permission request of a conversation that Android's dialog is showing right now. */
    private var asking: PermissionBroker.Request? = null

    /** The tile or complication tapped while Talova is open: the screens start it, after their checks. */
    private val relaunches = MutableSharedFlow<Launch>(extraBufferCapacity = 1)

    private val askPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // Granted as the feature needs it: either location, but contacts and calling both.
        asking?.let { PermissionBroker.answer(it, WatchFeatures.granted(this, it.feature)) }
        asking = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        val launch = if (savedInstanceState == null) launchOf(intent) else Launch.OPEN
        // With diagnostics on (see Diagnostics), a chain of routes to open, for tests and store
        // screenshots: `adb shell am start -n net.devemperor.talova/.MainActivity --es routes settings`.
        // Only on the first start: a new app language recreates the activity with the same intent.
        val routes = if (Diagnostics.enabled && savedInstanceState == null) {
            intent.getStringExtra(EXTRA_ROUTES)?.split(',')?.filter(String::isNotBlank).orEmpty()
        } else {
            emptyList()
        }
        setContent { TalovaUi(launch, routes, relaunches) }
        // With diagnostics on, records the sample conversation's voice in every language (see SampleVoices).
        if (Diagnostics.enabled && savedInstanceState == null && intent.getBooleanExtra(EXTRA_RECORD_SAMPLE, false)) {
            lifecycleScope.launch { SampleVoices.record(this@MainActivity) }
        }
        // A conversation needs a watch feature the user has not allowed yet (see PermissionBroker).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                PermissionBroker.pending.collect { request ->
                    if (request != null && request !== asking) {
                        asking = request
                        askPermission.launch(request.permissions.toTypedArray())
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        PermissionBroker.hostVisible = true
        // Back from a permission dialog or the system settings: the step permission may have changed.
        lifecycleScope.launch { StepTracking.sync(this@MainActivity) }
    }

    override fun onStop() {
        PermissionBroker.hostVisible = false
        // The monthly budget may have changed, which watch faces show against the month's cost.
        TalovaComplicationService.refresh(this)
        super.onStop()
    }

    /** The tile or complication was tapped while Talova was already open. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // With diagnostics on, a question typed into the running conversation, for tests on an emulator
        // whose microphone hears nothing: `adb shell am start -n …/.MainActivity --es say "…"`.
        if (Diagnostics.enabled) intent.getStringExtra(EXTRA_SAY)?.let(SessionHub::say)
        when (val again = launchOf(intent)) {
            Launch.TALK -> relaunches.tryEmit(again)
            else -> Unit
        }
    }

    private fun launchOf(intent: Intent?): Launch {
        val action = intent?.getStringExtra(TalovaTileService.EXTRA_ACTION) ?: intent?.action
        return when {
            // With diagnostics on, the introduction's sample conversation at once, for tests and store pictures.
            Diagnostics.enabled && intent?.getBooleanExtra(EXTRA_DEMO, false) == true -> Launch.SAMPLE
            action == ACTION_TALK -> Launch.TALK
            else -> Launch.OPEN
        }
    }

    companion object {
        const val ACTION_TALK = "net.devemperor.talova.action.TALK"
        private const val EXTRA_DEMO = "demo"
        private const val EXTRA_ROUTES = "routes"
        private const val EXTRA_SAY = "say"
        private const val EXTRA_RECORD_SAMPLE = "record_sample"

        fun talkIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_TALK)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
