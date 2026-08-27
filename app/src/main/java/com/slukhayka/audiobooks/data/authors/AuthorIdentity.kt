package com.slukhayka.audiobooks.data.authors

import com.slukhayka.audiobooks.data.facets.FacetIdentity
import java.text.Normalizer
import java.util.Locale

data class CanonicalAuthorIdentity(
    val id: String,
    val displayName: String,
    val normalizedName: String
)

data class AuthorAliasClaim(
    val authorId: String,
    val normalizedAlias: String,
    val rawAlias: String,
    val sourceId: String,
    val observedAt: Long = 0
)

data class AuthorAssertionIndex(
    val author: CanonicalAuthorIdentity,
    val aliases: List<AuthorAliasClaim>
)

/**
 * Deterministic, conservative author identity. Work names merge only after
 * Unicode/case/whitespace/apostrophe normalization; no transliteration or
 * fuzzy matching can join two people. A trusted Metadata Assertion may bind
 * explicit aliases to its already-canonical id.
 */
object AuthorIdentity {
    private val spaces = Regex("\\s+")
    private val apostrophes = Regex("['ʼ`´‘’]")

    const val MAX_NAME_LENGTH = 120
    const val MAX_ALIASES = 24

    fun fromWorkName(rawName: String): CanonicalAuthorIdentity {
        val displayName = displayName(rawName)
        require(displayName.isNotEmpty()) { "Author name is blank" }
        val normalizedName = normalizedName(displayName)
        return CanonicalAuthorIdentity(
            id = FacetIdentity.boundedId("author", normalizedName),
            displayName = displayName,
            normalizedName = normalizedName
        )
    }

    fun fromAssertion(
        canonicalId: String,
        displayName: String,
        aliases: List<String>,
        sourceId: String,
        observedAt: Long = 0
    ): AuthorAssertionIndex {
        require(canonicalId.isNotBlank() && canonicalId.length <= 80)
        require(sourceId.isNotBlank() && sourceId.length <= 80)
        require(observedAt >= 0)
        val author = CanonicalAuthorIdentity(
            id = canonicalId,
            displayName = displayName(displayName).also { require(it.isNotEmpty()) },
            normalizedName = normalizedName(displayName)
        )
        val boundedAliases = aliases
            .asSequence()
            .map(::displayName)
            .filter(String::isNotEmpty)
            .distinctBy(::normalizedName)
            .filterNot { normalizedName(it) == author.normalizedName }
            .take(MAX_ALIASES - 1)
            .toList() + author.displayName
        val claims = boundedAliases
            .asSequence()
            .map { rawAlias ->
                AuthorAliasClaim(
                    authorId = canonicalId,
                    normalizedAlias = normalizedName(rawAlias),
                    rawAlias = rawAlias,
                    sourceId = sourceId,
                    observedAt = observedAt
                )
            }
            .toList()
        return AuthorAssertionIndex(author, claims)
    }

    fun normalizedName(rawName: String): String = displayName(rawName)
        .replace(apostrophes, "’")
        .lowercase(Locale.ROOT)

    /**
     * Prefix-search keys for every word boundary. They are derived index
     * terms, not new identity evidence: `Тарас Шевченко` remains one alias,
     * while both `тарас…` and `шевченко…` can use the same B-tree range.
     */
    fun searchKeys(rawName: String): List<String> {
        val normalized = normalizedName(rawName)
        if (normalized.isBlank()) return emptyList()
        val words = normalized.split(' ').filter(String::isNotBlank)
        return words.indices.map { words.drop(it).joinToString(" ") }.distinct().take(MAX_ALIASES)
    }

    fun searchableQuery(rawQuery: String): String? = normalizedName(rawQuery)
        .takeIf { query -> query.count(Char::isLetterOrDigit) >= MIN_MEANINGFUL_CHARACTERS }

    private fun displayName(rawName: String): String = Normalizer.normalize(rawName, Normalizer.Form.NFKC)
        .trim()
        .replace(spaces, " ")
        .take(MAX_NAME_LENGTH)

    private const val MIN_MEANINGFUL_CHARACTERS = 2
}
