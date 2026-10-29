package net.devemperor.parley.features

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import net.devemperor.parley.core.tools.WatchFeature

/**
 * How a watch feature stands with its Android permission, as the settings show it. [SYSTEM_SETTING]:
 * a special access no dialog can grant (notifications), only a system screen.
 */
enum class Access { ALLOWED, ASKS_FIRST, BLOCKED, SYSTEM_SETTING }

/** The Android permissions behind each watch feature, and whether they are granted. */
object WatchFeatures {

    /**
     * The runtime permissions a feature asks for; none for those that need none, and none for the
     * notifications, whose access is a system setting (see NotificationReader).
     */
    fun permissions(feature: WatchFeature): List<String> = when (feature) {
        // Either one: coarse location answers "the weather here" too.
        WatchFeature.LOCATION -> listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        // Wear OS 6 replaced the body sensors permission with one per kind of health data.
        WatchFeature.HEART_RATE -> listOf(if (Build.VERSION.SDK_INT >= 36) READ_HEART_RATE else Manifest.permission.BODY_SENSORS)
        WatchFeature.STEPS -> listOf(Manifest.permission.ACTIVITY_RECOGNITION)
        WatchFeature.CALENDAR -> listOf(Manifest.permission.READ_CALENDAR)
        // Both: the number from the contacts, the call itself.
        WatchFeature.CALLS -> listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE)
        // The weather asks for the location only when it is for where the watch is (see ToolRunner).
        WatchFeature.WEATHER, WatchFeature.PRESSURE, WatchFeature.ALARM, WatchFeature.MUSIC, WatchFeature.APPS,
        WatchFeature.NOTIFICATIONS,
        -> emptyList()
    }

    fun granted(context: Context, feature: WatchFeature): Boolean {
        fun has(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        return when (feature) {
            WatchFeature.NOTIFICATIONS -> NotificationReader.allowed(context)
            WatchFeature.LOCATION -> permissions(feature).any(::has)
            else -> permissions(feature).all(::has)
        }
    }

    /**
     * Whether this watch can give Parley the feature at all. Notification access needs a system screen
     * many watches lack; without it (and without access granted by adb) the feature stays off.
     */
    fun available(context: Context, feature: WatchFeature): Boolean =
        feature != WatchFeature.NOTIFICATIONS || NotificationReader.allowed(context) || NotificationReader.settingsIntent(context) != null

    /**
     * Android shows no dialog any more once the user said no twice; [asked] tells that apart from a
     * permission never asked for, which also has no rationale yet.
     */
    fun access(activity: Activity, feature: WatchFeature, asked: Boolean): Access = when {
        granted(activity, feature) -> Access.ALLOWED
        feature == WatchFeature.NOTIFICATIONS -> Access.SYSTEM_SETTING
        asked && permissions(feature).none(activity::shouldShowRequestPermissionRationale) -> Access.BLOCKED
        else -> Access.ASKS_FIRST
    }

    private const val READ_HEART_RATE = "android.permission.health.READ_HEART_RATE"
}
