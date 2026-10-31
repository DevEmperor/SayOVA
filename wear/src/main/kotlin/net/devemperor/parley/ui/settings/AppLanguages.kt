package net.devemperor.parley.ui.settings

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * The language of Parley's screens, chosen in Parley itself instead of deep in the watch's settings.
 * Android keeps the choice per app (Android 13, Wear OS 4, and newer) and restarts the screens in it.
 */
object AppLanguages {
    /** The languages the app comes in: `localeFilters` in wear/build.gradle.kts. */
    val TAGS = listOf("en", "de", "fr", "es", "it", "pt-BR", "nl", "pl", "sv", "tr", "ja", "ko", "zh-CN")

    val supported: Boolean get() = Build.VERSION.SDK_INT >= 33

    /** The chosen language as one of [TAGS]; null while Parley follows the watch. */
    fun current(context: Context): String? {
        if (Build.VERSION.SDK_INT < 33) return null
        val chosen = context.getSystemService(LocaleManager::class.java).applicationLocales.get(0) ?: return null
        return TAGS.firstOrNull { it.equals(chosen.toLanguageTag(), ignoreCase = true) }
            ?: TAGS.firstOrNull { Locale.forLanguageTag(it).language == chosen.language }
    }

    /**
     * [context] speaking the chosen language. Screens get it from Android; the tile and complication
     * services got it only at times (a watch face showed "Reden" one moment and "Talk" the next).
     */
    fun localized(context: Context): Context {
        val tag = current(context) ?: return context
        val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        return context.createConfigurationContext(config)
    }

    /** Switches to [tag], or back to the watch's language for null; Android recreates the screens. */
    fun set(context: Context, tag: String?) {
        if (Build.VERSION.SDK_INT < 33) return
        context.getSystemService(LocaleManager::class.java).applicationLocales =
            if (tag == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
    }

    /** A language's own name for itself ("Deutsch", "日本語"), the way language pickers show them. */
    fun name(tag: String): String = when (tag) {
        "zh-CN" -> "简体中文"
        else -> Locale.forLanguageTag(tag).let { it.getDisplayName(it) }.replaceFirstChar { it.titlecase() }
    }
}
