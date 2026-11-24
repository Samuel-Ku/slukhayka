package com.slukhayka.audiobooks

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.fragment.app.FragmentActivity
import java.util.Locale

/**
 * Spec-45 (#405) — the UI language of the app itself. This is deliberately
 * distinct from the content-language preference (which languages of
 * narrations to show): the UI language never filters content and the content
 * preference never translates the interface.
 *
 * [AppLocale.SYSTEM] follows the device locale (the Activity context is left
 * unpinned) WHEN that language has a complete resource set; a language still
 * being translated is pinned to Ukrainian instead — see [SYSTEM_FOLLOWS].
 * [AppLocale.UKRAINIAN]/[AppLocale.ENGLISH] pin the Activity
 * context so app strings AND Material/embedded library resources speak one
 * language. The default is [AppLocale.SYSTEM] (spec-45 #405 R7 #514): a new
 * install follows the device, so an English-system device speaks English
 * (US15) and a Ukrainian device speaks Ukrainian — the pre-setting behavior
 * was Ukrainian-only because no English resources existed yet, not because
 * the app pinned one.
 */
enum class AppLocale(val tag: String) {
    /**
     * Follow the device locale when its language is in [SYSTEM_FOLLOWS];
     * otherwise keep the coherent Ukrainian UI instead of pinning a
     * half-translated one.
     */
    SYSTEM(""),
    UKRAINIAN("uk"),
    ENGLISH("en"),

    /**
     * #1389 — the third batch completed the translation, so Polish is now a
     * real interface language: it is in [SYSTEM_FOLLOWS] (a Polish device
     * follows the device) and selectable on the interface-language screen.
     */
    POLISH("pl");

    companion object {
        /**
         * #1387 — the languages whose resource set is COMPLETE enough for
         * [SYSTEM] to follow the device.
         *
         * Polish was translated in three sequential batches
         * (#1387/#1388/#1389) into `values-pl`. `pl` entered this set only with
         * the LAST batch, after the coverage guard
         * (`PolishCoverageGuardTest`) proved that every string the app can
         * reach has a Polish value: a partly translated language shows two
         * languages on one screen, which is exactly the #1385 defect that made
         * a partial translation worse than none. This set is the switch, not a
         * comment — the coverage guard fails if `pl` is here with a missing
         * line, and `AppLocaleTest` pins that a Polish device now follows
         * Polish instead of falling back to Ukrainian.
         */
        val SYSTEM_FOLLOWS: Set<String> = setOf("uk", "en", "pl")

        /**
         * Unknown or absent stored values follow the system. An EXPLICITLY
         * stored choice from any earlier store version is preserved — only
         * the absent default changed, never a saved selection (R7 #514).
         */
        fun fromStored(name: String?): AppLocale =
            entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}

/**
 * SharedPreferences-backed [AppLocale] store (spec-45 #405; R7 #514 keeps
 * the same file and key, so an already-saved explicit choice survives).
 * Persisted locally, never synced; read in [MainActivity.attachBaseContext]
 * and written synchronously by [AppLocaleApplier].
 */
class AppLocalePrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("app_locale_prefs", Context.MODE_PRIVATE)

    var locale: AppLocale
        get() = AppLocale.fromStored(prefs.getString(KEY_LOCALE, null))
        set(value) {
            prefs.edit().putString(KEY_LOCALE, value.name).apply()
        }

    private companion object {
        const val KEY_LOCALE = "app_locale"
    }
}

/**
 * Spec-45 (#405) R7 (#514) — applies a new App Locale choice immediately:
 * persist it, then drive the platform mechanism. API 33+ sets the app's
 * locales through [android.app.LocaleManager] (the OS re-creates the running
 * Activity with the new language). Older Android has no per-app locales API,
 * so the Activity recreates itself and [Context.withAppLocale] re-pins (or
 * un-pins for SYSTEM) in `attachBaseContext` — the compat fallback.
 */
object AppLocaleApplier {

    fun apply(activity: FragmentActivity, locale: AppLocale) {
        val prefs = AppLocalePrefs(activity)
        val previous = prefs.locale
        prefs.locale = locale
        if (Build.VERSION.SDK_INT >= 33) {
            val manager = activity.getSystemService(android.app.LocaleManager::class.java)
            val target = if (locale == AppLocale.SYSTEM) {
                LocaleList.getEmptyLocaleList()
            } else {
                LocaleList.forLanguageTags(locale.tag)
            }
            if (manager != null && manager.applicationLocales != target) {
                manager.applicationLocales = target
                return // The platform recreates the Activity for this change.
            }
        }
        // Legacy preferences can pin attachBaseContext while LocaleManager
        // still has an empty list. Empty → empty cannot trigger OS recreation.
        if (previous != locale) activity.recreate()
    }
}

/**
 * Returns the Activity context localized for [locale]. [AppLocale.SYSTEM]
 * returns [this] unchanged when the device language is one of
 * [AppLocale.SYSTEM_FOLLOWS] — the app follows the device, and since #1389
 * that includes Polish (all three translation batches landed). A concrete
 * locale pins the context (and with it Material + embedded Android controls),
 * so an explicitly chosen language never mixes with the device one.
 */
internal fun Context.withAppLocale(locale: AppLocale): Context {
    val targetTag = when (locale) {
        AppLocale.SYSTEM -> {
            val deviceLanguage = resources.configuration.locales[0].language
            if (deviceLanguage in AppLocale.SYSTEM_FOLLOWS) return this
            AppLocale.UKRAINIAN.tag
        }
        else -> locale.tag
    }
    val localeTag = Locale.forLanguageTag(targetTag)
    val configuration = Configuration(resources.configuration).apply {
        setLocale(localeTag)
        setLayoutDirection(localeTag)
    }
    return createConfigurationContext(configuration)
}
