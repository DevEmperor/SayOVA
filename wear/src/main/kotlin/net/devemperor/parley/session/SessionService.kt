package net.devemperor.parley.session

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import net.devemperor.parley.Diagnostics
import net.devemperor.parley.MainActivity
import net.devemperor.parley.R
import net.devemperor.parley.audio.Haptics
import net.devemperor.parley.core.prompt.Instructions
import net.devemperor.parley.core.provider.ProviderId
import net.devemperor.parley.core.provider.openai.OpenAiWebSearch
import net.devemperor.parley.core.session.ErrorKind
import net.devemperor.parley.core.session.SessionConfig
import net.devemperor.parley.core.session.SessionError
import net.devemperor.parley.core.session.Sessions
import net.devemperor.parley.core.tools.ParleyTools
import net.devemperor.parley.demo.DemoSession
import net.devemperor.parley.parley
import net.devemperor.parley.tile.ParleyTileService
import java.time.ZonedDateTime

/**
 * Keeps a conversation alive while the screen is off or another app is in front. A foreground service
 * of type microphone is the only way Android lets an app keep recording then; the ongoing activity
 * puts a Parley icon on the watch face that leads straight back.
 */
class SessionService : LifecycleService() {

    private var engine: ConversationEngine? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> if (engine == null) {
                // Android refuses a microphone service without the permission; the screens ask first,
                // this only guards against a start from a stale tile or shortcut.
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
                    runCatching { goForeground() }.isFailure
                ) {
                    SessionHub.publish(ConversationUiState(phase = Phase.ERROR, error = SessionError(ErrorKind.REJECTED, getString(R.string.error_no_microphone))))
                    stopSelf()
                    return START_NOT_STICKY
                }
                begin(textOnly = intent.getBooleanExtra(EXTRA_TEXT_ONLY, false), demo = Diagnostics.enabled && intent.getBooleanExtra(EXTRA_DEMO, false))
            }
            ACTION_STOP -> engine?.end() ?: stopEverything()
        }
        return START_NOT_STICKY
    }

    private fun begin(textOnly: Boolean, demo: Boolean) = lifecycleScope.launch {
        val app = parley
        val settings = app.settings.current()
        val provider = settings.providerInfo
        val apiKey = if (demo) "demo" else app.keys.get(provider.id)
        if (apiKey == null) {
            SessionHub.publish(ConversationUiState(phase = Phase.ERROR, error = SessionError(ErrorKind.AUTH, "No key for ${provider.displayName} yet."), textOnly = textOnly))
            stopEverything()
            return@launch
        }
        if (!demo) app.network.acquire()
        val model = provider.model(settings.modelId())
        val canSearch = settings.webSearch
        val config = SessionConfig(
            apiKey = apiKey,
            model = model.id,
            voice = settings.voiceId(),
            instructions = Instructions.build(
                persona = settings.persona,
                customPersona = settings.customPersona,
                language = settings.language,
                now = ZonedDateTime.now(),
                textOnly = textOnly,
                canSearch = canSearch,
            ),
            tools = ParleyTools.standard,
            webSearch = canSearch,
            textOnly = textOnly,
        )
        val client = app.network.client(app.http)
        val session = if (demo) DemoSession(textOnly) else Sessions.create(provider.id, config, client)
        val search = if (provider.id == ProviderId.OPENAI && canSearch && !demo) {
            ToolRunner.searchWith(OpenAiWebSearch(client), apiKey)
        } else {
            null
        }
        val newEngine = ConversationEngine(
            context = this@SessionService,
            scope = lifecycleScope,
            session = session,
            model = model,
            providerLabel = "${provider.displayName} · ${model.label}",
            providerKey = provider.id.key,
            options = ConversationEngine.Options(
                textOnly = textOnly,
                pushToTalk = settings.pushToTalk,
                bargeIn = settings.bargeIn,
                autoEndSeconds = settings.autoEndSeconds,
                showCost = settings.showCost,
                saveHistory = settings.historyDays != 0,
                speechVolume = settings.speechVolume,
                soundOff = settings.soundOff,
            ),
            toolRunner = ToolRunner(this@SessionService, search),
            haptics = Haptics(this@SessionService) { settings.haptics },
            transport = app.network.transport,
            onSave = { conversation, usd, talkMs ->
                conversation?.let { app.history.save(it) }
                // Demo conversations count too: only adb can start them (see Diagnostics), and tests
                // check the statistics with them.
                app.usage.record(usd, talkMs)
                ParleyTileService.refresh(this@SessionService)
            },
            onVolume = { level -> app.settings.update { it.copy(speechVolume = level) } },
            onFinished = { stopEverything() },
        )
        engine = newEngine
        SessionHub.engine = newEngine
        launch { newEngine.state.collectLatest { SessionHub.publish(it) } }
        newEngine.start()
    }

    private fun stopEverything() {
        parley.network.release()
        SessionHub.engine = null
        engine = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        engine?.end()
        SessionHub.engine = null
        super.onDestroy()
    }

    private fun goForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(this, 1, stopIntent(this), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_parley_mono)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_text))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(R.drawable.ic_call_end, getString(R.string.action_end), stop)
        OngoingActivity.Builder(applicationContext, NOTIFICATION_ID, builder)
            .setStaticIcon(R.drawable.ic_parley_mono)
            .setTouchIntent(open)
            .setStatus(Status.Builder().addTemplate(getString(R.string.ongoing_status)).build())
            .build()
            .apply(applicationContext)
        val notification: Notification = builder.build()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }

    companion object {
        private const val ACTION_START = "net.devemperor.parley.START"
        private const val ACTION_STOP = "net.devemperor.parley.STOP"
        private const val EXTRA_TEXT_ONLY = "text_only"
        private const val EXTRA_DEMO = "demo"
        private const val CHANNEL = "conversation"
        private const val NOTIFICATION_ID = 7

        fun startIntent(context: Context, textOnly: Boolean, demo: Boolean): Intent =
            Intent(context, SessionService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_TEXT_ONLY, textOnly)
                .putExtra(EXTRA_DEMO, demo)

        fun stopIntent(context: Context): Intent = Intent(context, SessionService::class.java).setAction(ACTION_STOP)
    }
}
