package com.slukhayka.audiobooks.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.slukhayka.audiobooks.R
import java.util.Locale

/**
 * Spec-51 (#742) — the one place a content language becomes a reader-facing
 * name. The two flagship languages keep their curated resource strings
 * (already localized in both App Locales); every wider language of the
 * multilingual catalog renders through the platform's own display names in
 * the CURRENT App Locale, so «de» reads «Німецька» in Ukrainian and «German»
 * in English without a 48-language resource duplication.
 *
 * Pure over an explicit [locale], so the fallback rule is JVM-testable.
 */
fun contentLanguageDisplayName(tag: String, locale: Locale): String {
    val name = Locale.forLanguageTag(tag).getDisplayLanguage(locale)
    // An unrecognized tag degrades to its own code — honest, never blank.
    if (name.isBlank() || name.equals(tag, ignoreCase = true)) return tag
    return name.replaceFirstChar { it.titlecase(locale) }
}

/** The label a row/chip shows for [tag] in the App Locale. */
@Composable
internal fun contentLanguageLabel(tag: String): String = when (tag) {
    "uk" -> stringResource(R.string.content_language_uk)
    "en" -> stringResource(R.string.content_language_en)
    else -> contentLanguageDisplayName(tag, Locale.getDefault())
}
