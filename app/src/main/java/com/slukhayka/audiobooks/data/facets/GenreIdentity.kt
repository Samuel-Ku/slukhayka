package com.slukhayka.audiobooks.data.facets

import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

/** Stable local identity of one normalized genre facet. */
data class NormalizedGenre(val id: String, val label: String)

/**
 * Pure genre normalization shared by migration, local writes and later
 * shared-facet hydration. Raw source text remains beside this derived value.
 */
object GenreIdentity {
    private val separators = Regex("[·,/;|]+")
    private val spaces = Regex("\\s+")

    private val canonical = mapOf(
        "фентезі" to NormalizedGenre("fantasy", "Фентезі"),
        "фантазія" to NormalizedGenre("fantasy", "Фентезі"),
        "фантастика" to NormalizedGenre("science-fiction", "Фантастика"),
        "наукова фантастика" to NormalizedGenre("science-fiction", "Фантастика"),
        "sci-fi" to NormalizedGenre("science-fiction", "Фантастика"),
        "science fiction" to NormalizedGenre("science-fiction", "Фантастика"),
        "детектив" to NormalizedGenre("detective", "Детективи"),
        "детективи" to NormalizedGenre("detective", "Детективи"),
        "поезія" to NormalizedGenre("poetry", "Поезія"),
        "поема" to NormalizedGenre("poetry", "Поезія"),
        "казка" to NormalizedGenre("fairy-tale", "Казка"),
        "сучасна проза" to NormalizedGenre("contemporary-prose", "Сучасна проза")
    )
    private val nonGenres = setOf("4read каталог", "усі жанри", "all genres")

    fun fromSourceText(rawText: String): List<NormalizedGenre> =
        rawText.split(separators)
            .mapNotNull(::normalizeOne)
            .distinctBy { it.id }

    private fun normalizeOne(raw: String): NormalizedGenre? {
        val normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC)
            .trim()
            .replace(spaces, " ")
            .lowercase(Locale.ROOT)
            .takeIf { it.isNotBlank() }
            ?: return null
        if (normalized in nonGenres) return null
        canonical[normalized]?.let { return it }
        val label = normalized.replaceFirstChar { char ->
            if (char.isLowerCase()) char.titlecase(Locale.ROOT) else char.toString()
        }.take(MAX_LABEL_LENGTH)
        return NormalizedGenre(
            id = FacetIdentity.boundedId("genre", normalized),
            label = label
        )
    }

    private const val MAX_LABEL_LENGTH = 80
}

/** Separate extension seam for author/narrator identity; no fuzzy merges. */
object FacetIdentity {
    private val spaces = Regex("\\s+")

    fun normalizedText(raw: String): String? = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        .trim()
        .replace(spaces, " ")
        .lowercase(Locale.ROOT)
        .takeIf { it.isNotBlank() }

    fun boundedId(kind: String, normalized: String): String =
        "$kind-${sha256(normalized).take(16)}"

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
