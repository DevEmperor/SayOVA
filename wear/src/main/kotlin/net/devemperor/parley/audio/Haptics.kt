package net.devemperor.parley.audio

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** One short, distinct pattern per moment, so the state can be felt without looking. */
class Haptics(context: Context, private val enabled: () -> Boolean) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }

    /** The conversation is open: ready to listen. */
    fun listening() = play(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))

    /** The answer starts speaking. */
    fun answering() = play(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))

    /** A tool did something on the watch (timer, alarm, note). */
    fun action() = play(VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK))

    fun ended() = play(VibrationEffect.createWaveform(longArrayOf(0, 40, 80, 25), intArrayOf(0, 180, 0, 90), -1))

    fun error() = play(VibrationEffect.createWaveform(longArrayOf(0, 120, 90, 120), intArrayOf(0, 255, 0, 255), -1))

    private fun play(effect: VibrationEffect) {
        if (enabled()) runCatching { vibrator?.vibrate(effect) }
    }
}
