package com.slukhayka.audiobooks.data.search

/** Query-time interpretation of existing index fields; no second index or guessed metadata. */
internal object SearchMatch {
    data class Result(val rank: Int, val correction: String? = null)

    fun tokens(query: String): List<String> = SearchIndexNormalize.titleField(query)
        .split(' ').filter { it.isNotBlank() }.take(10)

    fun match(query: String, title: String, author: String, series: String, narrator: String): Result? =
        matchNormalized(tokens(query), listOf(
            SearchIndexNormalize.titleField(title), SearchIndexNormalize.personField(author),
            SearchIndexNormalize.titleField(series), SearchIndexNormalize.personField(narrator)
        ))

    fun matchIndexed(queryTokens: List<String>, title: String, author: String, series: String, narrator: String): Result? =
        matchNormalized(queryTokens, listOf(title, author, series, SearchIndexNormalize.personField(narrator)))

    fun matchesAnyToken(queryTokens: List<String>, narrator: String): Boolean {
        val words = SearchIndexNormalize.personField(narrator).split(' ')
        return queryTokens.any { token -> words.any { word ->
            word.startsWith(token) || (token.length >= 3 && oneEdit(token, word))
        } }
    }

    private fun matchNormalized(queryTokens: List<String>, fields: List<String>): Result? {
        if (queryTokens.isEmpty()) return null
        val words = fields.flatMap { it.split(' ') }
        val corrected = queryTokens.toMutableList()
        var edits = 0
        for ((i, token) in queryTokens.withIndex()) {
            if (words.any { it.startsWith(token) }) continue
            if (token.length < 3 || ++edits > 1) return null
            corrected[i] = words.firstOrNull { oneEdit(token, it) } ?: return null
        }
        val normalizedQuery = queryTokens.joinToString(" ")
        if (edits == 0 && fields[0] == normalizedQuery) return Result(0)
        val matchedTokens = if (edits == 0) queryTokens else corrected
        val fieldIndex = fields.indexOfFirst { field ->
            val fieldWords = field.split(' ')
            matchedTokens.all { token -> fieldWords.any { it.startsWith(token) } }
        }
        // A corrected title is more useful than a coincidental author prefix;
        // exact titles and direct title-prefix matches always remain first.
        val rank = when (fieldIndex) {
            0 -> if (edits == 0) 1 else 2
            1 -> if (edits == 0) 3 else 4
            2 -> if (edits == 0) 5 else 6
            3 -> if (edits == 0) 7 else 8
            else -> if (edits == 0) 9 else 10
        }
        return Result(rank, corrected.joinToString(" ").takeIf { edits > 0 })
    }

    /** At most one insertion, deletion, substitution or adjacent transposition. */
    private fun oneEdit(a: String, b: String): Boolean {
        if (kotlin.math.abs(a.length - b.length) > 1 || a == b) return false
        var i = 0
        while (i < minOf(a.length, b.length) && a[i] == b[i]) i++
        return when {
            a.length < b.length -> a.substring(i) == b.substring(i + 1)
            a.length > b.length -> a.substring(i + 1) == b.substring(i)
            a.substring(i + 1) == b.substring(i + 1) -> true
            i + 1 < a.length && a[i] == b[i + 1] && a[i + 1] == b[i] ->
                a.substring(i + 2) == b.substring(i + 2)
            else -> false
        }
    }

    /** One edit affects at most two chunks; at least one of three survives. */
    fun anchors(query: String): List<String>? {
        val longest = tokens(query).filter { it.length >= 3 }.maxByOrNull { it.length } ?: return null
        val first = longest.length / 3
        val second = longest.length * 2 / 3
        return listOf(longest.substring(0, first), longest.substring(first, second), longest.substring(second))
            .map { "%$it%" }
    }
}
