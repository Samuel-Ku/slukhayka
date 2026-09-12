package com.slukhayka.audiobooks.data.reviews

import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.PopularityAssertionEntity
import com.slukhayka.audiobooks.data.metadata.PopularityAssertionPolicy
import com.slukhayka.audiobooks.testing.FakeAudiobookDao
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #739 — the bounded, TTL'd pass that fills the local listener aggregate of
 * the library rating from the shared reviews: fresh rows are skipped, absent
 * reviews clear a stale row, failures leave the previous evidence intact, and
 * an invalid rating never becomes a vote.
 */
class LibraryRatingRefreshTest {

    private val now = 1_700_000_000_000L

    private fun book(id: String, title: String, mergeKey: String, workId: String? = null) =
        AudiobookEntity(
            id = id,
            title = title,
            author = "Автор",
            narrator = "",
            description = "",
            coverDrawableRes = 0,
            genre = "",
            sourceUrl = ""
        ).also {
            it.mergeKey = mergeKey
            it.workId = workId
        }

    private fun review(workId: String, rating: Int, uid: String = "u1") =
        ListenerReview(
            workId = workId,
            uid = uid,
            authorName = "Слухач",
            rating = rating,
            createdAt = 1L
        )

    private class FakeStore(
        var byWork: Map<String, List<ListenerReview>> = emptyMap(),
        var failing: Boolean = false
    ) : ListenerReviewsStore {
        override suspend fun queryWorkDocuments(workId: String): List<Map<String, Any>> = docs(listOf(workId))

        override suspend fun queryWorksDocuments(workIds: List<String>): List<Map<String, Any>> = docs(workIds)

        private fun docs(ids: List<String>): List<Map<String, Any>> {
            if (failing) throw IllegalStateException("network down")
            return ids.flatMap { id -> byWork[id].orEmpty().map { ListenerReviewCodec.toMap(it) } }
        }

        override suspend fun enqueueDocument(
            documentId: String,
            document: Map<String, Any>
        ): ReviewWriteReceipt = ReviewWriteReceipt.Rejected

        override suspend fun removeDocument(documentId: String): Boolean = false
    }

    private suspend fun listenerRows(dao: FakeAudiobookDao) =
        dao.popularityAssertions(PopularityAssertionEntity.KIND_LISTENER_RATING)

    @Test
    fun `a due work receives the shared rating aggregate`() = runBlocking {
        val dao = FakeAudiobookDao(listOf(book("b1", "Кобзар", "кобзар|шевченко", workId = "w1")))
        val store = FakeStore(
            byWork = mapOf("w1" to listOf(review("w1", 5), review("w1", 3, uid = "u2")))
        )

        val updated = LibraryRatingRefresh(dao, store, clock = { now }).refreshIfDue()

        assertEquals(1, updated)
        val row = listenerRows(dao).single()
        assertEquals("кобзар|шевченко", row.mergeKey)
        assertEquals("8:2", row.rawValue)
        assertEquals(PopularityAssertionPolicy.LISTENER_SOURCE_ID, row.sourceId)
    }

    @Test
    fun `a fresh aggregate is skipped by the TTL`() = runBlocking {
        val dao = FakeAudiobookDao(listOf(book("b1", "Кобзар", "кобзар|шевченко", workId = "w1")))
        PopularityAssertionPolicy.listenerRatingRecord("кобзар|шевченко", 5, 1, now - 60_000L)!!
            .let { dao.upsertPopularityAssertions(listOf(it)) }
        val store = FakeStore(byWork = mapOf("w1" to listOf(review("w1", 2))))

        val updated = LibraryRatingRefresh(dao, store, clock = { now }).refreshIfDue()

        assertEquals(0, updated)
        assertEquals("the fresh row is untouched", "5:1", listenerRows(dao).single().rawValue)
    }

    @Test
    fun `a read with no reviews writes nothing and keeps the last known aggregate`() = runBlocking {
        val dao = FakeAudiobookDao(listOf(book("b1", "Кобзар", "кобзар|шевченко", workId = "w1")))
        PopularityAssertionPolicy.listenerRatingRecord("кобзар|шевченко", 5, 1, now - 3L * 24 * 60 * 60 * 1000)!!
            .let { dao.upsertPopularityAssertions(listOf(it)) }

        val updated = LibraryRatingRefresh(dao, FakeStore(byWork = emptyMap()), clock = { now }).refreshIfDue()

        assertEquals(0, updated)
        // The seam cannot tell an empty result from a failure, so the last
        // known observation stays — and nothing is invented as a zero.
        assertEquals("5:1", listenerRows(dao).single().rawValue)
    }

    @Test
    fun `a failing store leaves the previous evidence intact`() = runBlocking {
        val dao = FakeAudiobookDao(listOf(book("b1", "Кобзар", "кобзар|шевченко", workId = "w1")))
        PopularityAssertionPolicy.listenerRatingRecord("кобзар|шевченко", 5, 1, now - 3L * 24 * 60 * 60 * 1000)!!
            .let { dao.upsertPopularityAssertions(listOf(it)) }

        val updated = LibraryRatingRefresh(dao, FakeStore(failing = true), clock = { now }).refreshIfDue()

        assertEquals(0, updated)
        assertEquals("5:1", listenerRows(dao).single().rawValue)
    }

    @Test
    fun `invalid ratings never become votes`() = runBlocking {
        val dao = FakeAudiobookDao(listOf(book("b1", "Кобзар", "кобзар|шевченко", workId = "w1")))
        val store = FakeStore(byWork = mapOf("w1" to listOf(review("w1", 0), review("w1", 7, uid = "u2"))))

        val updated = LibraryRatingRefresh(dao, store, clock = { now }).refreshIfDue()

        assertEquals(0, updated)
        assertTrue(listenerRows(dao).isEmpty())
    }

    @Test
    fun `the pass is bounded by its batch limit`() = runBlocking {
        val dao = FakeAudiobookDao(
            listOf(
                book("b1", "А", "a|x", workId = "w1"),
                book("b2", "Б", "b|x", workId = "w2"),
                book("b3", "В", "c|x", workId = "w3")
            )
        )
        val store = FakeStore(
            byWork = mapOf(
                "w1" to listOf(review("w1", 5)),
                "w2" to listOf(review("w2", 5)),
                "w3" to listOf(review("w3", 5))
            )
        )

        val updated = LibraryRatingRefresh(dao, store, clock = { now }, batchLimit = 2).refreshIfDue()

        assertEquals(2, updated)
        assertEquals(2, listenerRows(dao).size)
    }

    @Test
    fun `the aggregate is encoded and decoded honestly`() {
        val record = PopularityAssertionPolicy.listenerRatingRecord("к|а", sum = 8, count = 2, observedAt = now)!!
        assertEquals("8:2", record.rawValue)
        assertEquals(8 to 2, PopularityAssertionPolicy.listenerRatingValue(record.rawValue))

        assertNull(PopularityAssertionPolicy.listenerRatingRecord("", 5, 1, now))
        assertNull("an empty pool is an absent claim", PopularityAssertionPolicy.listenerRatingRecord("к|а", 0, 0, now))
        assertNull(PopularityAssertionPolicy.listenerRatingValue("nonsense"))
        assertNull(PopularityAssertionPolicy.listenerRatingValue("0:0"))
        assertNull("a mean outside 1..5 is corrupt", PopularityAssertionPolicy.listenerRatingValue("99:2"))
    }

    @Test
    fun `the aggregate pool obeys the same flat mean`() {
        // Sources 4.0 + listeners (sum 8, count 2) → (4 + 8) / 3 = 4.0, count 3.
        val combined = CombinedAverage.averageWithListenerAggregate(listOf(4.0), listenerSum = 8, listenerCount = 2)!!
        assertEquals(4.0, combined.value, 0.0001)
        assertEquals(3, combined.count)

        assertNull(CombinedAverage.averageWithListenerAggregate(emptyList(), 0, 0))
        assertNotNull(CombinedAverage.averageWithListenerAggregate(emptyList(), 4, 1))
    }
}
