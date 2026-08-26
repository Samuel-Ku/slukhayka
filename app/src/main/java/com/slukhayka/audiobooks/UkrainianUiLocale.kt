package com.slukhayka.audiobooks

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * The Android client currently ships one product language: Ukrainian.
 * Pinning the Activity context also localizes Material and embedded Android
 * controls, whose library resources would otherwise follow the device locale
 * and mix Polish/English accessibility labels into the Ukrainian interface.
 */
internal fun Context.withUkrainianUiLocale(): Context {
    val locale = Locale.forLanguageTag("uk")
    val configuration = Configuration(resources.configuration).apply {
        setLocale(locale)
        setLayoutDirection(locale)
    }
    return createConfigurationContext(configuration)
}
