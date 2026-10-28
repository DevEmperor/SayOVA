package net.devemperor.parley

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import net.devemperor.parley.session.SessionHub
import net.devemperor.parley.tile.ParleyTileService
import net.devemperor.parley.ui.Launch
import net.devemperor.parley.ui.ParleyUi
import net.devemperor.parley.ui.setup.hasMicrophone

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        val launch = if (savedInstanceState == null) launchOf(intent) else Launch.OPEN
        // With diagnostics on (see Diagnostics), a chain of routes to open, for tests and store
        // screenshots: `adb shell am start -n net.devemperor.parley/.MainActivity --es routes settings`.
        val routes = if (Diagnostics.enabled) {
            intent.getStringExtra(EXTRA_ROUTES)?.split(',')?.filter(String::isNotBlank).orEmpty()
        } else {
            emptyList()
        }
        setContent { ParleyUi(launch, routes) }
    }

    /** The tile or complication was tapped while Parley was already open. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
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

        fun talkIntent(context: Context, textOnly: Boolean = false): Intent =
            Intent(context, MainActivity::class.java)
                .setAction(if (textOnly) ACTION_TEXT else ACTION_TALK)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
