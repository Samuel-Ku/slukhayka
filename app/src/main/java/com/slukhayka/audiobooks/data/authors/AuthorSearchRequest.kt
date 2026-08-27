package com.slukhayka.audiobooks.data.authors

import kotlinx.coroutines.CancellationException

/** Keeps a cancelled stale keystroke from publishing over the next request. */
suspend fun authorMatchesOrEmpty(
    query: String,
    lookup: suspend (String) -> List<AuthorSummary>
): List<AuthorSummary> = try {
    lookup(query)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    emptyList()
}
