package com.slukhayka.audiobooks.data

import java.util.Locale

/**
 * #405 (spec: Багатомовні видання) — the ONE normalizer of Edition content
 * languages.
 *
 * An Edition's `language` is a BCP-47 primary tag (`uk`, `en`, `de`, …).
 * Adapters never invent one: they hand their raw claim (a source's own label
 * like `English`, a code like `en-US`, or an ISO-639-3 code like `eng`) to
 * [normalize], and only a KNOWN mapping yields a value. Unknown → `null` →
 * the Edition stores `""` (unknown), which the language filter NEVER hides —
 * a source that does not declare a language must not make its books vanish
 * under a monoglot's filter (US17). Pure JVM so adapters, tests and the web
 * mirror share one rule.
 */
object LanguageCode {

    /** Ukrainian content — the language every verified Ukrainian source claims. */
    const val UKRAINIAN = "uk"

    /** English content — claimed by the LibriVox adapter (T2/T3). */
    const val ENGLISH = "en"

    /**
     * Normalizes a raw language claim to its canonical BCP-47 primary tag.
     *
     * Accepted inputs (case-insensitive):
     *  - canonical primary tags: `uk`, `en`, `de`, … (kept verbatim);
     *  - full primary-tag strings: `en-US`, `uk_UA` (the primary tag wins);
     *  - full English names: `English`, `Ukrainian`, `German`, …;
     *  - ISO-639-3 / ISO-639-2 codes for the same languages: `eng`, `ukr`.
     *
     * Anything else — an unknown name, garbage, blank — is `null`. The caller
     * stores `""` for null: unknown, never guessed.
     */
    fun normalize(raw: String?): String? {
        if (raw == null) return null
        val cleaned = raw.trim().lowercase(Locale.ROOT)
        if (cleaned.isEmpty()) return null

        // Full primary-tag strings / locale variants: the primary tag wins.
        cleaned.splitToSequence('-', '_')
            .firstOrNull()
            ?.takeIf { it.matches(PRIMARY_TAG) }
            ?.let { tag ->
                BY_NAME[tag]?.let { return it }
                if (tag in CANONICAL_TAGS) return tag
                ISO_639_3[tag]?.let { return it }
            }

        // A full language name that is not already a code (e.g. "english").
        BY_NAME[cleaned]?.let { return it }
        ISO_639_3[cleaned]?.let { return it }
        if (cleaned.matches(PRIMARY_TAG) && cleaned in CANONICAL_TAGS) return cleaned
        return null
    }

    private val PRIMARY_TAG = Regex("^[a-z]{2,8}$")

    /** Two-letter canonical tags we know directly (verbatim pass-through). */
    private val CANONICAL_TAGS = setOf(
        "uk", "en", "de", "fr", "es", "ru", "pl", "it", "pt", "nl",
        "sv", "da", "no", "fi", "cs", "sk", "hu", "ro", "bg", "el",
        "hr", "sr", "sl", "et", "lv", "lt", "tr", "ar", "he", "zh", "ja"
    )

    /** Full-name (and primary-tag alias) → canonical tag. */
    private val BY_NAME = mapOf(
        "ukrainian" to UKRAINIAN,
        "uk" to UKRAINIAN,
        "english" to ENGLISH,
        "en" to ENGLISH,
        "german" to "de",
        "de" to "de",
        "french" to "fr",
        "fr" to "fr",
        "spanish" to "es",
        "es" to "es",
        "russian" to "ru",
        "ru" to "ru",
        "polish" to "pl",
        "pl" to "pl",
        "italian" to "it",
        "it" to "it",
        "portuguese" to "pt",
        "pt" to "pt",
        "dutch" to "nl",
        "nl" to "nl",
        "swedish" to "sv",
        "sv" to "sv",
        "danish" to "da",
        "da" to "da",
        "norwegian" to "no",
        "no" to "no",
        "finnish" to "fi",
        "fi" to "fi",
        "czech" to "cs",
        "cs" to "cs",
        "slovak" to "sk",
        "sk" to "sk",
        "hungarian" to "hu",
        "hu" to "hu",
        "romanian" to "ro",
        "ro" to "ro",
        "bulgarian" to "bg",
        "bg" to "bg",
        "greek" to "el",
        "el" to "el",
        "croatian" to "hr",
        "hr" to "hr",
        "serbian" to "sr",
        "sr" to "sr",
        "slovenian" to "sl",
        "sl" to "sl",
        "estonian" to "et",
        "et" to "et",
        "latvian" to "lv",
        "lv" to "lv",
        "lithuanian" to "lt",
        "lt" to "lt",
        "turkish" to "tr",
        "tr" to "tr",
        "arabic" to "ar",
        "ar" to "ar",
        "hebrew" to "he",
        "he" to "he",
        "chinese" to "zh",
        "zh" to "zh",
        "japanese" to "ja",
        "ja" to "ja"
    )

    /** ISO-639-3 / ISO-639-2 → canonical tag (the `eng`-style claims). */
    private val ISO_639_3 = mapOf(
        "ukr" to UKRAINIAN,
        "eng" to ENGLISH,
        "deu" to "de",
        "ger" to "de",
        "fra" to "fr",
        "fre" to "fr",
        "spa" to "es",
        "rus" to "ru",
        "pol" to "pl",
        "ita" to "it",
        "por" to "pt",
        "nld" to "nl",
        "dut" to "nl",
        "swe" to "sv",
        "dan" to "da",
        "nor" to "no",
        "fin" to "fi",
        "ces" to "cs",
        "cze" to "cs",
        "slk" to "sk",
        "slo" to "sk",
        "hun" to "hu",
        "ron" to "ro",
        "rum" to "ro",
        "bul" to "bg",
        "ell" to "el",
        "gre" to "el",
        "hrv" to "hr",
        "srp" to "sr",
        "slv" to "sl",
        "est" to "et",
        "lav" to "lv",
        "lit" to "lt",
        "tur" to "tr",
        "ara" to "ar",
        "heb" to "he",
        "zho" to "zh",
        "chi" to "zh",
        "jpn" to "ja"
    )
}
