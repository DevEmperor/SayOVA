package net.devemperor.parley

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
import kotlinx.coroutines.launch
import net.devemperor.parley.features.PermissionBroker
import net.devemperor.parley.features.WatchFeatures
import net.devemperor.parley.session.SessionHub
import net.devemperor.parley.tile.ParleyTileService
import net.devemperor.parley.ui.Launch
import net.devemperor.parley.ui.ParleyUi
import net.devemperor.parley.ui.setup.hasMicrophone

class MainActivity : ComponentActivity() {

    /** The permission request of a conversation that Android's dialog is showing right now. */
    private var asking: PermissionBroker.Request? = null

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
        // screenshots: `adb shell am start -n net.devemperor.parley/.MainActivity --es routes settings`.
        // Only on the first start: a new app language recreates the activity with the same intent.
        val routes = if (Diagnostics.enabled && savedInstanceState == null) {
            intent.getStringExtra(EXTRA_ROUTES)?.split(',')?.filter(String::isNotBlank).orEmpty()
        } else {
            emptyList()
        }
        setContent { ParleyUi(launch, routes) }
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
    }

    override fun onStop() {
        PermissionBroker.hostVisible = false
        super.onStop()
    }

    /** The tile or complication was tapped while Parley was already open. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // With diagnostics on, a question typed into the running conversation, for tests on an emulator
        // whose microphone hears nothing: `adb shell am start -n …/.MainActivity --es say "…"`.
        if (Diagnostics.enabled) intent.getStringExtra(EXTRA_SAY)?.let(SessionHub::say)
        when (launchOf(intent)) {
            Launch.TALK -> if (hasMicrophone() && parley.keys.connected.value.isNotEmpty()) SessionHub.start(this, textOnly = false)
            Launch.TEXT -> if (hasMicrophone() && parley.keys.connected.value.isNotEmpty()) SessionHub.start(this, textOnly = true)
            else -> Unit
        }
    }

    private fun launchOf(intent: Intent?): Launch {
        val action = intent?.getStringExtra(ParleyTileService.EXTRA_ACTION) ?: intent?.action
        return when {
            Diagnostics.enabled && intent?.getBooleanExtra(EXTRA_DEMO, false) == true -> Launch.DEMO
            action == ACTION_TALK -> Launch.TALK
            action == ACTION_TEXT -> Launch.TEXT
            else -> Launch.OPEN
        }
    }

    companion object {
        const val ACTION_TALK = "net.devemperor.parley.action.TALK"
        const val ACTION_TEXT = "net.devemperor.parley.action.TEXT"
        private const val EXTRA_DEMO = "demo"
        private const val EXTRA_ROUTES = "routes"
        private const val EXTRA_SAY = "say"

        fun talkIntent(context: Context, textOnly: Boolean = false): Intent =
            Intent(context, MainActivity::class.java)
                .setAction(if (textOnly) ACTION_TEXT else ACTION_TALK)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
