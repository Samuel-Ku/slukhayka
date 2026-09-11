package com.slukhayka.audiobooks.data.merge

/**
 * Spec-49 follow-up (2026-09-10) — the slug matcher behind the local
 * sitemap index: a Cyrillic Work (title + author, the MergeKey side) against
 * a transliterated Latin slug (the sitemap side, e.g. `igra-dzheralda-stiven-king`).
 *
 * Slug sources transliterate the SAME Cyrillic differently (Ukrainian vs
 * Russian conventions: `г`→h/g, `и`→y/i, `ж`→zh/j, `х`→kh/h, `ц`→ts/c,
 * `щ`→shch/sch, `ю`→iu/yu, `я`→ia/ya, `є`→ie/ye, `ї`→i/yi, endings drift too —
 * `igry` vs `igra`). One transliteration cannot match all of them, so the rule
 * is deliberately forgiving:
 *
 * 1. **Variant equality** — every Cyrillic token expands into its common Latin
 *    variants (a bounded, documented set, never a cartesian explosion).
 * 2. **Fuzzy token equality** — short edit distances for the long tokens,
 *    where a real transliteration drift lives (`oruell`/`orwell`,
 *    `igry`/`igra`).
 * 3. **Consonant skeleton equality** — vowels are the most volatile part of a
 *    transliteration; a matching consonant frame of at least three letters is
 *    a match (catches `dzheralda`/`dzherald`-style endings).
 *
 * The slug must be fully contained by the query's tokens, and at least one
 * token must match strongly — a one-token coincidence is not a Work.
 *
 * Pure JVM; no Android, no I/O.
 */
object SlugMatch {

    private val MULTI = mapOf(
        'а' to listOf("a"),
        'б' to listOf("b"),
        'в' to listOf("v", "w"),
        'г' to listOf("h", "g"),
        'ґ' to listOf("g"),
        'д' to listOf("d"),
        'е' to listOf("e"),
        'є' to listOf("ie", "ye", "e"),
        'ж' to listOf("zh", "j"),
        'з' to listOf("z"),
        'и' to listOf("y", "i"),
        'і' to listOf("i"),
        'ї' to listOf("i", "yi", "ji"),
        'й' to listOf("i", "y", "j"),
        'к' to listOf("k"),
        'л' to listOf("l"),
        'м' to listOf("m"),
        'н' to listOf("n"),
        'о' to listOf("o"),
        'п' to listOf("p"),
        'р' to listOf("r"),
        'с' to listOf("s"),
        'т' to listOf("t"),
        'у' to listOf("u"),
        'ф' to listOf("f", "ph"),
        'х' to listOf("kh", "h"),
        'ц' to listOf("ts", "c"),
        'ч' to listOf("ch"),
        'ш' to listOf("sh"),
        'щ' to listOf("shch", "sch"),
        'ь' to listOf(""),
        'ъ' to listOf(""),
        'ю' to listOf("iu", "yu", "u"),
        'я' to listOf("ia", "ya", "a"),
        'ы' to listOf("y"),
        'э' to listOf("e"),
        'ё' to listOf("e", "yo")
    )

    private const val VOWELS = "aeiouy"

    /**
     * All common Latin forms of one Cyrillic [token]: the primary
     * transliteration plus each single ambiguous switch applied once (a
     * bounded set, not a cartesian product). Latin tokens pass through.
     */
    fun variants(token: String): Set<String> {
        val normalized = token.lowercase()
        if (normalized.isEmpty()) return emptySet()
        if (normalized.none { it in MULTI }) return setOf(normalized)

        var forms = setOf("")
        for (char in normalized) {
            val options = MULTI[char] ?: listOf(char.toString())
            val next = linkedSetOf<String>()
            for (form in forms) {
                for (option in options) {
                    next += form + option
                }
            }
            // Bound the blow-up: keep the first four forms per step; the
            // dropped tails are re-derived by the fuzzy/skeleton rules.
            forms = if (next.size > 4) next.take(4).toSet() else next
        }
        return forms.filter { it.isNotEmpty() }.toSet()
    }

    /** The consonant frame of a Latin token; empty for vowel-only tokens. */
    fun skeleton(token: String): String =
        token.lowercase().filter { it !in VOWELS }

    /**
     * True when every significant token of [slug] is matched by the Work's
     * [title]/[author] and at least one match is strong.
     */
    fun slugMatches(slug: String, title: String, author: String): Boolean {
        val slugTokens = tokens(slug)
        if (slugTokens.isEmpty()) return false
        val queryForms = (tokens(title) + tokens(author))
            .flatMap { variants(it) }
            .toSet()

        var strong = false
        for (token in slugTokens) {
            val match = matchToken(token, queryForms)
            if (match == MatchKind.NONE) return false
            if (match == MatchKind.STRONG) strong = true
        }
        return strong
    }

    /** Slug tokenization: hyphens/underscores to spaces, drop noise tokens. */
    fun tokens(slug: String): List<String> {
        val cleaned = slug.lowercase()
            .removePrefix("audioknyha-")
            .replace('-', ' ')
            .replace('_', ' ')
            .replace(Regex("[^0-9a-zа-яіїєґ ]+"), " ")
        return cleaned.split(' ').filter { it.length > 1 }
    }

    private enum class MatchKind { NONE, WEAK, STRONG }

    private fun matchToken(slugToken: String, queryForms: Set<String>): MatchKind {
        val forms = variants(slugToken)
        if (forms.any { it in queryForms }) {
            return if (slugToken.length >= 4) MatchKind.STRONG else MatchKind.WEAK
        }
        var weak = false
        val slugSkeleton = skeleton(slugToken)
        for (query in queryForms) {
            val distance = editDistance(slugToken, query)
            val limit = when {
                query.length >= 9 -> 2
                query.length >= 5 -> 1
                else -> 0
            }
            if (distance <= limit) {
                return if (query.length >= 5) MatchKind.STRONG else MatchKind.WEAK
            }
            if (slugSkeleton.isNotEmpty() && slugSkeleton == skeleton(query)) {
                if (slugSkeleton.length >= 3 && query.length >= 5) return MatchKind.STRONG
                // The И→y/i ending drift on a short token (igra/igry): the
                // consonant frame survives, the vowel does not.
                if (slugSkeleton.length >= 2 && slugToken.length >= 4) weak = true
            }
        }
        return if (weak) MatchKind.WEAK else MatchKind.NONE
    }

    private fun editDistance(a: String, b: String): Int {
        val previous = IntArray(b.length + 1) { it }
        val current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + cost)
            }
            previous.indices.forEach { previous[it] = current[it] }
        }
        return previous[b.length]
    }
}
