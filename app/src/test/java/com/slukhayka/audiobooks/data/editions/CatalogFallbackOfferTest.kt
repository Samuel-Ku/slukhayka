package com.slukhayka.audiobooks.data.editions

import com.slukhayka.audiobooks.data.db.SourceEntity
import com.slukhayka.audiobooks.testing.FakeAudiobookDao
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * #530 — the real-data offer: the Source rows a book actually has become an
 * ordered, honestly classified candidate list, and a refused or cooling-down
 * source is left out of the offer without any row being touched.
 */
class CatalogFallbackOfferTest {

    private val bookId = "b1"

    private suspend fun seed(dao: FakeAudiobookDao, vararg sources: SourceEntity) {
        dao.insertSources(sources.toList())
    }

    private fun source(type: String, editionId: String?, url: String = "https://x/$type") = SourceEntity(
        id = "$type-$bookId",
        bookId = bookId,
        editionId = editionId,
        type = type,
        url = url
    )

    @Test
    fun `the offer orders the book's own sources by kind`() = runTest {
        val dao = FakeAudiobookDao()
        seed(
            dao,
            source("soundbooks", editionId = "edition-1"),
            source("sluhayua", editionId = "edition-1"),
            source("other", editionId = "edition-2")
        )
        val offer = CatalogFallbackOffer(dao, clock = { 1_000_000L })

        val candidates = offer.offer(bookId, currentSourceId = "soundbooks")

        assertEquals(
            listOf("soundbooks", "sluhayua", "other"),
            candidates.map { it.sourceId }
        )
        assertEquals(FallbackCandidateKind.CURRENT_DIRECT, candidates[0].kind)
        assertEquals(FallbackCandidateKind.ALTERNATE_DIRECT_SAME_EDITION, candidates[1].kind)
        assertEquals(FallbackCandidateKind.CONFIRMED_OTHER_EDITION, candidates[2].kind)
    }

    @Test
    fun `a refused source is left out of the offer`() = runTest {
        val dao = FakeAudiobookDao()
        seed(
            dao,
            source("soundbooks", editionId = "edition-1"),
            source("sluhayua", editionId = "edition-1")
        )
        val offer = CatalogFallbackOffer(dao, clock = { 1_000_000L })

        val candidates = offer.offer(bookId, currentSourceId = "soundbooks", refused = setOf("sluhayua"))

        assertEquals(listOf("soundbooks"), candidates.map { it.sourceId })
        assertEquals("the row itself is untouched", 2, dao.getSourcesForBookSync(bookId).size)
    }

    @Test
    fun `a cooling-down source is left out until its window passes`() = runTest {
        val file = File.createTempFile("cooldown", ".tsv").apply { delete() }
        try {
            val dao = FakeAudiobookDao()
            seed(
                dao,
                source("soundbooks", editionId = "edition-1"),
                source("sluhayua", editionId = "edition-1")
            )
            var now = 1_000_000L
            val cooldown = SourceCooldownStore(file)
            cooldown.recordFailure("sluhayua", now)
            val offer = CatalogFallbackOffer(dao, cooldown, clock = { now })

            assertEquals(
                listOf("soundbooks"),
                offer.offer(bookId, currentSourceId = "soundbooks").map { it.sourceId }
            )
            assertEquals("the row itself is untouched", 2, dao.getSourcesForBookSync(bookId).size)

            // Once the bounded window passes the source is offered again.
            now += SourceCooldownPolicy.FIRST_FAILURE_MS
            assertEquals(
                listOf("soundbooks", "sluhayua"),
                offer.offer(bookId, currentSourceId = "soundbooks").map { it.sourceId }
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun `another edition never auto-starts and a book without sources has no offer`() = runTest {
        val dao = FakeAudiobookDao()
        seed(dao, source("soundbooks", editionId = "edition-1"), source("other", editionId = "edition-2"))
        val offer = CatalogFallbackOffer(dao, clock = { 1_000_000L })

        // The Source in use is the same Edition and maps safely, so it is the
        // auto-start; the other Edition only ever appears as a confirmed offer.
        assertEquals(
            "soundbooks",
            offer.autoStartable(bookId, currentSourceId = "soundbooks")?.sourceId
        )
        assertNull(offer.autoStartable("no-sources", currentSourceId = null))
        assertEquals(emptyList<FallbackCandidate>(), offer.offer("no-sources"))
    }
}
