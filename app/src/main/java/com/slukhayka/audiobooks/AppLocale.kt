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
 * unpinned); [AppLocale.UKRAINIAN]/[AppLocale.ENGLISH] pin the Activity
 * context so app strings AND Material/embedded library resources speak one
 * language. The default is [AppLocale.SYSTEM] (spec-45 #405 R7 #514): a new
 * install follows the device, so an English-system device speaks English
 * (US15) and a Ukrainian device speaks Ukrainian — the pre-setting behavior
 * was Ukrainian-only because no English resources existed yet, not because
 * the app pinned one.
 */
enum class AppLocale(val tag: String) {
    /** Follow the device locale without pinning the context. */
    SYSTEM(""),
    UKRAINIAN("uk"),
    ENGLISH("en");

    companion object {
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
 * returns [this] unchanged — the app follows the device. Any concrete locale
 * pins the context (and with it Material + embedded Android controls), so a
 * Polish device running a Ukrainian locale never mixes Polish library labels
 * into the Ukrainian interface.
 */
internal fun Context.withAppLocale(locale: AppLocale): Context {
    if (locale == AppLocale.SYSTEM) return this
    val localeTag = Locale.forLanguageTag(locale.tag)
    val configuration = Configuration(resources.configuration).apply {
        setLocale(localeTag)
        setLayoutDirection(localeTag)
    }
    return createConfigurationContext(configuration)
}
