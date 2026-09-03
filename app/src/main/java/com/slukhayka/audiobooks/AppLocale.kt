package com.slukhayka.audiobooks

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
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
 * language. The default is [AppLocale.UKRAINIAN] — exactly the behavior the
 * app shipped with before this setting existed — so nothing changes until a
 * listener opts in.
 */
enum class AppLocale(val tag: String) {
    /** Follow the device locale — no pinning, no context recreation. */
    SYSTEM(""),
    UKRAINIAN("uk"),
    ENGLISH("en");

    companion object {
        fun fromStored(name: String?): AppLocale =
            entries.firstOrNull { it.name == name } ?: UKRAINIAN
    }
}

/**
 * SharedPreferences-backed [AppLocale] store (spec-45 #405). Persisted
 * locally, never synced; read once per process start in
 * [MainActivity.attachBaseContext] and synchronously on change, so a locale
 * switch applies on the next Activity creation.
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
