package net.devemperor.parley.features

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Parley's notification listener: lets the model read the notifications on the watch, and tells what
 * is playing (media sessions are open to notification listeners only). Android connects it once the
 * user granted notification access; Parley reads nothing in the background, only when asked.
 */
class NotificationReader : NotificationListenerService() {

    override fun onListenerConnected() {
        connected = this
    }

    override fun onListenerDisconnected() {
        if (connected === this) connected = null
    }

    companion object {
        @Volatile private var connected: NotificationReader? = null

        fun component(context: Context) = ComponentName(context, NotificationReader::class.java)

        fun allowed(context: Context): Boolean =
            context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(component(context))

        /** The notifications on the watch now; waits briefly for Android to connect the listener. */
        suspend fun current(context: Context): List<StatusBarNotification>? {
            if (!allowed(context)) return null
            if (connected == null) requestRebind(component(context))
            val reader = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                while (connected == null) delay(100)
                connected
            } ?: return null
            return runCatching { reader.activeNotifications.toList() }.getOrNull()
        }

        private const val CONNECT_TIMEOUT_MS = 3_000L
    }
}
