package com.slukhayka.audiobooks.ui

import com.slukhayka.audiobooks.data.authors.AuthorSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchAuthorRefreshTest {
    @Test(timeout = 10_000)
    fun `delayed initial authors cannot overwrite the count after gap fill`() = runBlocking {
        val initialStarted = CompletableDeferred<Unit>()
        val releaseInitial = CompletableDeferred<Unit>()
        val sourcesFinished = CompletableDeferred<Unit>()
        val published = mutableListOf<Int>()
        var reads = 0
        val request = async {
            searchWithAuthorRefresh(
                readAuthors = {
                    if (++reads == 1) {
                        initialStarted.complete(Unit)
                        releaseInitial.await()
                        listOf(AuthorSummary("king", "Стівен Кінг", "стівен кінг", 3))
                    } else listOf(AuthorSummary("king", "Стівен Кінг", "стівен кінг", 5))
                },
                publishAuthors = { published += it.single().workCount },
                searchSources = {
                    initialStarted.await()
                    sourcesFinished.complete(Unit)
                }
            )
        }
        sourcesFinished.await()
        releaseInitial.complete(Unit)
        request.await()
        assertEquals(listOf(3, 5), published)
    }

}
