package net.devemperor.parley

import android.util.Log

/**
 * Test hooks and the conversation log (`adb logcat -s ParleyEvent ParleyPair`): always on in debug
 * builds, and in a release build only after `adb shell setprop log.tag.Parley DEBUG`. Only adb can set
 * that property, not an app or the user, so the release build that is tested is the one that ships,
 * yet its demo mode, routes and logs stay out of everyone else's reach.
 */
object Diagnostics {
    val enabled: Boolean get() = BuildConfig.DEBUG || Log.isLoggable("Parley", Log.DEBUG)
}
