package com.slukhayka.audiobooks.data.facets

import com.slukhayka.audiobooks.data.metadata.SubmissionAccessMode
import com.slukhayka.audiobooks.data.metadata.SubmissionChapter
import com.slukhayka.audiobooks.data.metadata.SubmissionPublication
import com.slukhayka.audiobooks.testing.FakeSharedBookMetaStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-0035 / #605 — the submission consumption lane (the FacetDeltaSync
 * precedent): one bounded chain pulls published documents into the local
 * projection, the cursor advances only past committed pages, a failing store
 * contributes nothing, and the lane never throws.
 */
class SubmissionDeltaSyncTest {

    private class RecordingWriter : SubmissionProjectionWriter {
        val applied = mutableListOf<SubmissionPublication>()

        override suspend fun apply(publications: List<SubmissionPublication>) {
            applied += publications
        }
    }

    private val store = FakeSharedBookMetaStore()
    private val writer = RecordingWriter()
    private val cursorStore = InMemorySubmissionSyncCursorStore()
    private val sync = SubmissionDeltaSync(store, writer, cursorStore)

    private fun publication(url: String, submittedAt: Long) = SubmissionPublication(
        sourceUrl = url,
        accessMode = SubmissionAccessMode.YOUTUBE,
        title = "Острів Дума",
        author = "Стівен Кінг",
        chapters = listOf(SubmissionChapter("Розділ 1", "https://www.youtube.com/watch?v=6XIPkMFZf-0")),
        verifiedAt = submittedAt,
        submittedAt = submittedAt,
        submitterId = "device-1"
    )

    @Test
    fun `one chain applies every published document and advances the cursor`() = runBlocking {
        store.publishSubmission(publication("https://youtu.be/a", submittedAt = 100))
        store.publishSubmission(publication("https://youtu.be/b", submittedAt = 200))

        val result = sync.syncAvailablePages()

        assertEquals(SubmissionDeltaSync.ChainResult(pagesApplied = 1, publicationsApplied = 2), result)
        assertEquals(2, writer.applied.size)
        val cursor = cursorStore.load()
        assertTrue("the cursor advanced past the last committed document", cursor != null && cursor.submittedAt == 200L)
    }

    @Test
    fun `two pages chain to the end`() = runBlocking {
        store.publishSubmission(publication("https://youtu.be/a", submittedAt = 100))
        store.publishSubmission(publication("https://youtu.be/b", submittedAt = 200))

        val result = sync.syncAvailablePages(pageSize = 1)

        assertEquals(SubmissionDeltaSync.ChainResult(pagesApplied = 2, publicationsApplied = 2), result)
        assertEquals(2, writer.applied.size)
    }

    @Test
    fun `empty store is NoChanges and the cursor stays put`() = runBlocking {
        assertEquals(SubmissionDeltaSync.PageResult.NoChanges, sync.syncPage())
        assertNull(cursorStore.load())
    }

    @Test
    fun `a failing store contributes nothing and never throws`() = runBlocking {
        val failingStore = FakeSharedBookMetaStore(throwOnSubmissionPage = true)
        val lane = SubmissionDeltaSync(failingStore, writer, InMemorySubmissionSyncCursorStore())
        assertEquals(SubmissionDeltaSync.PageResult.Failed, lane.syncPage())
        assertEquals(SubmissionDeltaSync.ChainResult(0, 0), lane.syncAvailablePages())
        assertTrue(writer.applied.isEmpty())
    }

    @Test
    fun `a failing writer does not advance the cursor`() = runBlocking {
        store.publishSubmission(publication("https://youtu.be/a", submittedAt = 100))
        val broken = object : SubmissionProjectionWriter {
            override suspend fun apply(publications: List<SubmissionPublication>) {
                throw IllegalStateException("projection full")
            }
        }
        val lane = SubmissionDeltaSync(store, broken, cursorStore)
        assertEquals(SubmissionDeltaSync.PageResult.Failed, lane.syncPage())
        assertNull(cursorStore.load())
    }
}