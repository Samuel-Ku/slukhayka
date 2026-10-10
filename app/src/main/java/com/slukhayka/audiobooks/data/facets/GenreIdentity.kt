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

    /**
     * The canonical vocabulary. A genre joins this map only when a source
     * really CLAIMS its wording and a captured page proves it (#1053); every
     * other text keeps a bounded identity derived from what it itself says.
     *
     * #702 (T4) added the seven shelves the sources already claim — жахи,
     * пригоди, біографії, саморозвиток, історична проза, дитяча література й
     * любовні романи — each observed in a captured page (4read's menu and book
     * pages, sound-books' categories, lihtar's library). «Класика» і
     * «нон-фікшн» тут НЕМАЄ: жодне джерело цих слів не заявляє, а назвати їх
     * означало б вигадати словник (ADR-0014).
     *
     * #1053 added «Попаданці», and it too is a claim, not a wish: chitaka.com.ua
     * lists the shelf under Фантастика with its own page
     * (`/zhanryi/fantastika/popadancy/`, captured 2026-10-10 — the genre index
     * carries `title="Попаданці"`, the shelf page says «Попаданці» in its own
     * title and heading). 4read uses the same word only as a tag («попаданці»,
     * «попаданці в інші світи»), never in the genre row the app reads. The
     * entry fixes the identity ahead of the claim reaching the filters: no
     * source the app currently reads genres from claims the word yet, so the
     * chip appears when one does — and it appears under a stable id instead of
     * a hash, without the late-dictionary migration #702 (T4) had to write.
     */
    private val canonical = mapOf(
        "фентезі" to NormalizedGenre("fantasy", "Фентезі"),
        "фантазія" to NormalizedGenre("fantasy", "Фентезі"),
        "фантастика" to NormalizedGenre("science-fiction", "Фантастика"),
        "наукова фантастика" to NormalizedGenre("science-fiction", "Фантастика"),
        "sci-fi" to NormalizedGenre("science-fiction", "Фантастика"),
        "science fiction" to NormalizedGenre("science-fiction", "Фантастика"),
        "жахи" to NormalizedGenre("horror", "Жахи"),
        "детектив" to NormalizedGenre("detective", "Детективи"),
        "детективи" to NormalizedGenre("detective", "Детективи"),
        "поезія" to NormalizedGenre("poetry", "Поезія"),
        "поема" to NormalizedGenre("poetry", "Поезія"),
        "казка" to NormalizedGenre("fairy-tale", "Казка"),
        "сучасна проза" to NormalizedGenre("contemporary-prose", "Сучасна проза"),
        "пригоди" to NormalizedGenre("adventure", "Пригоди"),
        "біографії" to NormalizedGenre("biography", "Біографії"),
        "саморозвиток" to NormalizedGenre("self-development", "Саморозвиток"),
        "історична проза" to NormalizedGenre("historical-prose", "Історична проза"),
        "дитячі" to NormalizedGenre("childrens-literature", "Дитяча література"),
        "дитяча література" to NormalizedGenre("childrens-literature", "Дитяча література"),
        "любовні романи" to NormalizedGenre("romance", "Любовні романи"),
        "попаданці" to NormalizedGenre("portal-fantasy", "Попаданці")
    )
    private val nonGenres = setOf("каталог", "4read каталог", "усі жанри", "all genres")

    /** Published canonical identities by id — for callers that must not invent one. */
    val canonicalIdentities: Map<String, NormalizedGenre> = canonical.values.associateBy { it.id }

    fun fromSourceText(rawText: String): List<NormalizedGenre> =
        rawText.split(separators)
            .mapNotNull(::normalizeOne)
            .distinctBy { it.id }

    /** Keeps a shared canonical id while deriving its truthful bounded label from one raw claim. */
    fun fromCanonical(genreId: String, rawText: String): NormalizedGenre? {
        val candidates = fromSourceText(rawText)
        return candidates.firstOrNull { it.id == genreId }
            ?: candidates.singleOrNull()?.copy(id = genreId)
    }

    /**
     * #702 (T4) — every claim inside one raw genre text, each with the id it
     * carried BEFORE the dictionary listed it ([ClaimedGenreIdentity.priorHashedId]).
     *
     * A stored row keeps its raw claim verbatim, so re-normalization reads the
     * claim itself instead of guessing from a hash; this is the only place that
     * can answer "what did this text turn into back then" without carrying a
     * second copy of the old vocabulary.
     */
    internal fun claimIdentities(rawText: String): List<ClaimedGenreIdentity> =
        rawText.split(separators).mapNotNull { fragment ->
            val key = normalizedKey(fragment) ?: return@mapNotNull null
            normalizeKey(key)?.let { genre ->
                ClaimedGenreIdentity(genre = genre, priorHashedId = FacetIdentity.boundedId("genre", key))
            }
        }

    private fun normalizeOne(raw: String): NormalizedGenre? = normalizedKey(raw)?.let(::normalizeKey)

    private fun normalizeKey(key: String): NormalizedGenre? {
        if (key in nonGenres) return null
        canonical[key]?.let { return it }
        val label = key.replaceFirstChar { char ->
            if (char.isLowerCase()) char.titlecase(Locale.ROOT) else char.toString()
        }.take(MAX_LABEL_LENGTH)
        return NormalizedGenre(id = FacetIdentity.boundedId("genre", key), label = label)
    }

    private fun normalizedKey(raw: String): String? = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        .trim()
        .replace(spaces, " ")
        .lowercase(Locale.ROOT)
        .takeIf { it.isNotBlank() }

    private const val MAX_LABEL_LENGTH = 80
}

/** One claimed genre and the id that same claim carried while it was not yet listed. */
internal data class ClaimedGenreIdentity(val genre: NormalizedGenre, val priorHashedId: String)

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
