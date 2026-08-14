package com.example.data.collection

import java.util.Locale
import java.text.Normalizer

/**
 * Spec-16 T1 (#107) — the string rules that let a curated list entry match a
 * catalog book across source spellings.
 *
 * Two sides of the comparison (entry and catalog row) are run through the
 * same pipe, so the fold is symmetric: case-fold, parenthesized publisher
 * annotations stripped, loose Cyrillic transliteration variants folded
 * (і/ї/й→и, ё/є/э→е, ґ→г, ы→и, ь/ъ dropped), diacritics decomposed and
 * dropped (precomposed é/ñ/ç…), punctuation replaced with whitespace,
 * runs collapsed. «Коцюбинський» matches «Коцюбинский»; «Ґарсія Маркес»
 * matches «Гарсия Маркес»; «Кобзар (А-БА-БА-ГА-ЛА-МА-ГА)» matches «Кобзар».
 *
 * Pure JVM so the rules are pinned by fixture tests.
 */
object CollectionNormalizer {

    private val CYRILLIC_FOLD = mapOf(
        // Vowel alternations — the common Ukrainian↔Russian/Russian↔translit
        // variants («Коцюбинський» ↔ «Коцюбинский», «Михайло» ↔ «Михаило»).
        'і' to 'и', 'ї' to 'и', 'й' to 'и',
        'ё' to 'е', 'є' to 'е', 'э' to 'е',
        'ґ' to 'г', 'ы' to 'и'
    )

    // Hard signs never carry meaning for matching — dropped, not folded.
    private val HARD_SIGNS = setOf('ь', 'ъ')

    private val PARENTHESIS = Regex("\\([^)]*\\)")
    private val NON_ALNUM = Regex("[^\\p{L}\\p{N} ]")
    private val WHITESPACE_RUNS = Regex("\\s+")

    fun normalize(text: String): String = text
        .trim()
        .lowercase(Locale.ROOT)
        .replace(PARENTHESIS, " ")
        .let(::foldCyrillic)
        .let(::stripDiacritics)
        .replace(NON_ALNUM, " ")
        .replace(WHITESPACE_RUNS, " ")
        .trim()

    private fun foldCyrillic(value: String): String = buildString(value.length) {
        for (ch in value) {
            if (ch in HARD_SIGNS) continue
            append(CYRILLIC_FOLD[ch] ?: ch)
        }
    }

    private fun stripDiacritics(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
}