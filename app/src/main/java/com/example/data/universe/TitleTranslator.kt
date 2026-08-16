package com.example.data.universe

/**
 * Spec-26 T1 (#175) — the title-translation seam behind the Wikidata search
 * fallback. A book whose uk title finds nothing on Wikidata (no uk label, and
 * no ru/en label under the uk spelling) gets its title translated uk → ru/en
 * and the search retried with the translated string.
 *
 * The seam is pure JVM (an interface — the provider and its fixture tests
 * never touch Android); the production implementation is ML Kit Translation
 * ([MlKitTitleTranslator]) — on-device, free, no API key.
 */
interface TitleTranslator {

    /**
     * Translates [text] into [targetLanguage] ("ru" / "en" — the Wikidata
     * search languages), or null when the translation is unavailable (no
     * model, failed download, failed translation, blank result) — the caller
     * degrades silently, exactly like a failing fetch.
     */
    suspend fun translate(text: String, targetLanguage: String): String?
}
