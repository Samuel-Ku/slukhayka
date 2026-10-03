package com.slukhayka.audiobooks.ui

import com.slukhayka.audiobooks.data.authors.AuthorSummary
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Publish local authors promptly, then read again after the mirror grows. */
internal suspend fun searchWithAuthorRefresh(
    readAuthors: suspend () -> List<AuthorSummary>,
    publishAuthors: (List<AuthorSummary>) -> Unit,
    searchSources: suspend () -> Unit
) = coroutineScope {
    suspend fun refresh() {
        val authors = readAuthors()
        currentCoroutineContext().ensureActive()
        publishAuthors(authors)
    }
    val initialRead = launch { refresh() }
    searchSources()
    // An older read from this same query must finish before the fresh count.
    initialRead.join()
    refresh()
}
