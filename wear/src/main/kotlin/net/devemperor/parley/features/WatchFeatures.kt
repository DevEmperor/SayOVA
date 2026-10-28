package net.devemperor.parley.features

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import net.devemperor.parley.core.tools.WatchFeature

/** How a watch feature stands with its Android permission, as the settings show it. */
enum class Access { ALLOWED, ASKS_FIRST, BLOCKED }

/** The Android permissions behind each watch feature, and whether they are granted. */
object WatchFeatures {

    /** Any one of them is enough: coarse location answers "the weather here" too. */
    fun permissions(feature: WatchFeature): List<String> = when (feature) {
        WatchFeature.LOCATION -> listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        // Wear OS 6 replaced the body sensors permission with one per kind of health data.
        WatchFeature.HEART_RATE -> listOf(if (Build.VERSION.SDK_INT >= 36) READ_HEART_RATE else Manifest.permission.BODY_SENSORS)
        WatchFeature.STEPS -> listOf(Manifest.permission.ACTIVITY_RECOGNITION)
        WatchFeature.CALENDAR -> listOf(Manifest.permission.READ_CALENDAR)
    }

    fun granted(context: Context, feature: WatchFeature): Boolean =
        permissions(feature).any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /**
     * Android shows no dialog any more once the user said no twice; [asked] tells that apart from a
     * permission never asked for, which also has no rationale yet.
     */
    fun access(activity: Activity, feature: WatchFeature, asked: Boolean): Access = when {
        granted(activity, feature) -> Access.ALLOWED
        asked && permissions(feature).none(activity::shouldShowRequestPermissionRationale) -> Access.BLOCKED
        else -> Access.ASKS_FIRST
    }

    private const val READ_HEART_RATE = "android.permission.health.READ_HEART_RATE"
}
