package com.slukhayka.audiobooks.data.facets

/**
 * Spec-51 (#742) — the ONE ordering of a content-language list: Ukrainian
 * first, English second, everything else alphabetically. It mirrors the web
 * `availableLanguagesOf` rule exactly, so the filter screen, the First
 * Language Choice sheet and the Огляд chip all present languages in the same
 * order on both platforms. Blank entries never reach a list.
 */
fun orderContentLanguages(languages: Collection<String>): List<String> {
    val rank = mapOf("uk" to 0, "en" to 1)
    return languages.distinct()
        .filter { it.isNotBlank() }
        .sortedWith(compareBy({ rank[it] ?: 9 }, { it }))
}
