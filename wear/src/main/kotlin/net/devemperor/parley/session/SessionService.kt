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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import net.devemperor.parley.core.tools.WatchFeature
import net.devemperor.parley.core.usage.Budget
import net.devemperor.parley.core.usage.BudgetLevel
import net.devemperor.parley.core.usage.Usage
import net.devemperor.parley.demo.DemoSession
import net.devemperor.parley.features.WatchFeatures
import net.devemperor.parley.parley
import net.devemperor.parley.tile.ParleyTileService
import net.devemperor.parley.ui.labelRes
import java.time.YearMonth
import java.time.ZonedDateTime

/**
 * Keeps a conversation alive while the screen is off or another app is in front. A foreground service
 * of type microphone is the only way Android lets an app keep recording then; the ongoing activity
 * puts a Parley icon on the watch face that leads straight back.
 */
class SessionService : LifecycleService() {

    private var engine: ConversationEngine? = null
    private var notification: Notification? = null
    private var features: Set<WatchFeature> = emptySet()

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
                begin(
                    textOnly = intent.getBooleanExtra(EXTRA_TEXT_ONLY, false),
                    demo = Diagnostics.enabled && intent.getBooleanExtra(EXTRA_DEMO, false),
                    continueId = intent.getStringExtra(EXTRA_CONTINUE),
                )
            }
            ACTION_STOP -> engine?.end() ?: stopEverything()
        }
        return START_NOT_STICKY
    }

    private fun begin(textOnly: Boolean, demo: Boolean, continueId: String?) = lifecycleScope.launch {
        val app = parley
        val settings = app.settings.current()
        val earlier = continueId?.let(app.history::find)
        val provider = settings.providerInfo
        val apiKey = if (demo) "demo" else app.keys.get(provider.id)
        if (apiKey == null) {
            SessionHub.publish(ConversationUiState(phase = Phase.ERROR, error = SessionError(ErrorKind.AUTH, getString(R.string.error_no_key_for, provider.displayName)), textOnly = textOnly))
            stopEverything()
            return@launch
        }
        // Waking the Wi-Fi runs alongside opening the speaker and the microphone; only the connection
        // itself waits for it (usually it is up already, see NetworkSelector.prewarm).
        val network = if (demo) null else async { app.network.acquire() }
        val spentUsd = Usage.month(app.usage.days.value, YearMonth.now()).costUsd
        val chosen = provider.model(settings.modelId())
        // Near the budget the user may have Parley talk with the cheaper model, so what is left lasts.
        val model = if (settings.budgetSave && Budget.level(spentUsd, settings.budgetUsd) >= BudgetLevel.NEAR) provider.economyModel else chosen
        val canSearch = settings.webSearch
        val memory = app.memory.takeIf { settings.memory }
        features = if (demo) emptySet() else settings.features.filter { WatchFeatures.available(this@SessionService, it) }.toSet()
        widenForegroundType()
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
                earlier = earlier?.turns.orEmpty(),
                memory = memory?.current(),
                features = features,
            ),
            tools = ParleyTools.standard + (if (memory != null) ParleyTools.memory else emptyList()) + features.flatMap { it.tools },
            webSearch = canSearch,
            textOnly = textOnly,
            thinkingLevel = model.thinkingLevel,
        )
        val client = app.network.client(app.http)
        val session = if (demo) DemoSession(textOnly) else Sessions.create(provider.id, config, client)
        val search = if (provider.id == ProviderId.OPENAI && canSearch && !demo) {
            ToolRunner.searchWith(OpenAiWebSearch(client), apiKey)
        } else {
            null
        }
        // Off the main thread: opening the speaker, the call mode and the microphone takes long enough
        // to stall the orb growing into the conversation.
        val newEngine = withContext(Dispatchers.Default) {
            ConversationEngine(
                context = this@SessionService,
                scope = lifecycleScope,
                session = session,
                model = model,
                providerLabel = "${provider.displayName} · ${model.labelRes()?.let(::getString) ?: model.label}",
                providerKey = provider.id.key,
                options = ConversationEngine.Options(
                    textOnly = textOnly,
                    pushToTalk = settings.pushToTalk,
                    bargeIn = settings.bargeIn,
                    autoEndSeconds = settings.autoEndSeconds,
                    showCost = settings.showCost,
                    saveHistory = settings.historyDays != 0,
                    speechVolume = settings.speechVolume,
                    callVolume = settings.callVolume,
                    soundOff = settings.soundOff,
                    continues = earlier,
                    budgetUsd = settings.budgetUsd,
                    spentUsd = spentUsd,
                    economy = model != chosen,
                ),
                toolRunner = ToolRunner(this@SessionService, search, memory, features, onAllowed = ::widenForegroundType, http = client),
                haptics = Haptics(this@SessionService) { settings.haptics },
                transport = app.network.transport,
                onSave = { conversation, usd, talkMs ->
                    conversation?.let { app.history.save(it) }
                    // Demo conversations count too: only adb can start them (see Diagnostics), and tests
                    // check the statistics with them.
                    app.usage.record(usd, talkMs)
                    ParleyTileService.refresh(this@SessionService)
                },
                onVolume = { level, call -> app.settings.update { if (call) it.copy(callVolume = level) else it.copy(speechVolume = level) } },
                onFinished = { stopEverything() },
            )
        }
        engine = newEngine
        SessionHub.engine = newEngine
        launch { newEngine.state.collectLatest { SessionHub.publish(it) } }
        withContext(Dispatchers.Default) { newEngine.start() }
        network?.await()
        if (Diagnostics.enabled) android.util.Log.d("ParleyEvent", "connecting over ${app.network.transport.value} to ${model.id}")
        newEngine.connect()
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
        this.notification = notification
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }

    /**
     * With the screen off Parley runs in the background, where Android hands out the location and the
     * body sensors only to a foreground service of that type. A type may be claimed only once its
     * permission is granted, so this runs at the start and again whenever the user allows a feature.
     */
    private fun widenForegroundType() {
        val notification = notification ?: return
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (WatchFeature.LOCATION in features && WatchFeatures.granted(this, WatchFeature.LOCATION)) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        val health = listOf(WatchFeature.HEART_RATE, WatchFeature.STEPS).any { it in features && WatchFeatures.granted(this, it) }
        if (health && android.os.Build.VERSION.SDK_INT >= 34) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
        if (types == ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) return
        runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, types) }
            .onFailure { android.util.Log.w("Parley", "Foreground type $types refused", it) }
    }

    companion object {
        private const val ACTION_START = "net.devemperor.parley.START"
        private const val ACTION_STOP = "net.devemperor.parley.STOP"
        private const val EXTRA_TEXT_ONLY = "text_only"
        private const val EXTRA_DEMO = "demo"
        private const val EXTRA_CONTINUE = "continue"
        private const val CHANNEL = "conversation"
        private const val NOTIFICATION_ID = 7

        fun startIntent(context: Context, textOnly: Boolean, demo: Boolean, continueId: String? = null): Intent =
            Intent(context, SessionService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_TEXT_ONLY, textOnly)
                .putExtra(EXTRA_DEMO, demo)
                .putExtra(EXTRA_CONTINUE, continueId)

        fun stopIntent(context: Context): Intent = Intent(context, SessionService::class.java).setAction(ACTION_STOP)
    }
}
