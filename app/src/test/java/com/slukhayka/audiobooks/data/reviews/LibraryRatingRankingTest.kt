package com.slukhayka.audiobooks.data.reviews

import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.PopularityAssertionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #738 / ADR-0022 — the library rating ranks by the one honest combined
 * average: source ratings plus listener ratings, no fabricated zeros, and a
 * Work without any vote is absent.
 */
class LibraryRatingRankingTest {

    private fun book(
        id: String,
        title: String,
        author: String = "Автор",
        mergeKey: String = "mk-$id",
        workId: String? = null
    ) = AudiobookEntity(
        id = id,
        title = title,
        author = author,
        narrator = "",
        description = "",
        coverDrawableRes = 0,
        genre = "",
        sourceUrl = ""
    ).also {
        it.mergeKey = mergeKey
        it.workId = workId
    }

    private fun evidence(
        id: String,
        title: String,
        workKey: String = "mk-$id",
        sourceRatings: List<Double?> = emptyList(),
        listenerRatings: List<Int> = emptyList()
    ) = LibraryRatingEvidence(
        book = book(id, title, mergeKey = workKey),
        workKey = workKey,
        sourceRatings = sourceRatings,
        listenerRatings = listenerRatings
    )

    private fun rating(mergeKey: String, value: String, sourceId: String = "soundbooks") =
        PopularityAssertionEntity(
            id = "$sourceId-$mergeKey-$value",
            kind = PopularityAssertionEntity.KIND_RATING,
            mergeKey = mergeKey,
            rawValue = value,
            sourceId = sourceId,
            observedAt = 0L
        )

    @Test
    fun `a work without any vote is absent, never zero`() {
        val ranked = LibraryRatingRanking.rank(listOf(evidence("b", "Книга")))

        assertTrue(ranked.isEmpty())
    }

    @Test
    fun `source and listener votes share one flat mean`() {
        val ranked = LibraryRatingRanking.rank(
            listOf(
                // Sources 4.0 and 5.0 + listener 3 → (4+5+3)/3 = 4.0, count 3.
                evidence("b", "Книга", sourceRatings = listOf(4.0, 5.0), listenerRatings = listOf(3))
            )
        )

        assertEquals(4.0, ranked.single().average, 0.0001)
        assertEquals(3, ranked.single().count)
    }

    @Test
    fun `invalid listener ratings never poison the average`() {
        val ranked = LibraryRatingRanking.rank(
            listOf(evidence("b", "Книга", sourceRatings = listOf(5.0), listenerRatings = listOf(0, 7)))
        )

        assertEquals(5.0, ranked.single().average, 0.0001)
        assertEquals("only the real source vote counts", 1, ranked.single().count)
    }

    @Test
    fun `ordering is average, then vote count, then title`() {
        val ranked = LibraryRatingRanking.rank(
            listOf(
                evidence("a", "Альфа", sourceRatings = listOf(4.5)),
                evidence("b", "Бета", sourceRatings = listOf(4.5, 4.5)),
                evidence("c", "Гама", sourceRatings = listOf(5.0))
            )
        )

        assertEquals(listOf("Гама", "Бета", "Альфа"), ranked.map { it.book.title })
    }

    @Test
    fun `evidence joins works and assertions without inventing a source vote`() {
        val evidence = libraryRatingEvidence(
            books = listOf(
                book("b1", "Кобзар", mergeKey = "кобзар|шевченко"),
                book("b2", "Місто", mergeKey = "місто|підмогильний")
            ),
            ratingAssertions = listOf(
                rating("кобзар|шевченко", "4.7"),
                // An assertion for a Work the listener does not own is ignored.
                rating("замок|кафка", "4.9")
            )
        )

        val ranked = LibraryRatingRanking.rank(evidence)
        assertEquals(listOf("Кобзар"), ranked.map { it.book.title })
        assertEquals(4.7, ranked.single().average, 0.0001)
        assertEquals(1, ranked.single().count)
    }

    @Test
    fun `several editions of one work collapse to one rating row`() {
        val evidence = libraryRatingEvidence(
            books = listOf(
                book("b1", "Темна матерія", mergeKey = "темна|крауч"),
                book("b2", "Темна матерія", mergeKey = "темна|крауч")
            ),
            ratingAssertions = listOf(rating("темна|крауч", "4.0"))
        )

        assertEquals(1, evidence.size)
        assertEquals(1, LibraryRatingRanking.rank(evidence).size)
    }
}
