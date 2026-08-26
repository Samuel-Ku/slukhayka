package com.slukhayka.audiobooks.data.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FacetAssertionCodecTest {

    @Test
    fun `work assertion round-trips without rendition or personal facts`() {
        val assertion = FacetAssertion.Work(
            workId = "лісова-пісня|леся-українка",
            sourceId = "4read",
            author = FacetPerson("author-lesia", "Леся Українка", listOf("Лариса Косач")),
            genres = listOf(
                FacetGenre("drama", "Драма"),
                FacetGenre("fantasy", "Фентезі")
            ),
            seriesMemberships = listOf(FacetSeriesMembership("forest-cycle", 2)),
            observedAt = 1_700_000_000_000L,
            updatedAt = 41L
        )

        val encoded = FacetAssertionCodec.toMap(assertion)!!

        assertEquals(assertion, FacetAssertionCodec.fromMap(assertion.documentId, encoded))
        assertEquals(
            listOf(
                mapOf("id" to "drama", "rawText" to "Драма"),
                mapOf("id" to "fantasy", "rawText" to "Фентезі")
            ),
            encoded["genres"]
        )
        assertFalse(encoded.containsKey("genreIds"))
        assertFalse(encoded.containsKey("narrator"))
        assertFalse(encoded.containsKey("durationSeconds"))
        assertFalse(encoded.containsKey("uid"))
        assertFalse(encoded.containsKey("deviceId"))
        assertFalse(encoded.containsKey("history"))
        assertFalse(encoded.containsKey("streamUrl"))
        assertFalse(encoded.containsKey("description"))
    }

    @Test
    fun `edition assertion round-trips with rendition facts and expiring availability`() {
        val assertion = FacetAssertion.Edition(
            editionId = "8f12cafe1234",
            workId = "лісова-пісня|леся-українка",
            sourceId = "sluhay",
            narrator = FacetPerson("narrator-test", "Олена Тестова", listOf("О. Тестова")),
            language = "uk",
            durationRef = "8f12cafe1234",
            durationBucket = FacetDurationBucket.FIVE_TO_TEN_HOURS,
            chapterCount = 24,
            completeness = FacetCompleteness.FULL,
            availability = FacetAvailability(
                available = true,
                observedAt = 1_700_000_000_000L,
                ttlSeconds = 86_400L
            ),
            observedAt = 1_700_000_000_000L,
            updatedAt = 42L
        )

        val encoded = FacetAssertionCodec.toMap(assertion)!!

        assertEquals(assertion, FacetAssertionCodec.fromMap(assertion.documentId, encoded))
        assertTrue(assertion.availability!!.isFreshAt(1_700_086_399_999L))
        assertFalse(assertion.availability!!.isFreshAt(1_700_086_400_000L))
        assertFalse(encoded.containsKey("author"))
    }

    @Test
    fun `document identity is stable per entity and Source and separates kinds`() {
        val work = workAssertion()
        val repeat = work.copy(updatedAt = 99L)
        val anotherSource = work.copy(sourceId = "sluhay")
        val edition = editionAssertion(editionId = work.workId, workId = work.workId)

        assertEquals(work.documentId, repeat.documentId)
        assertEquals("work~work-id~4read", work.documentId)
        assertNotEquals(work.documentId, anotherSource.documentId)
        assertNotEquals(work.documentId, edition.documentId)
    }

    @Test
    fun `bounded maxima remain writable and one value beyond is a miss`() {
        val bounded = workAssertion().copy(
            author = FacetPerson(
                "author-id",
                "Автор",
                List(FacetAssertionLimits.MAX_ALIASES) { "Псевдонім $it" }
            ),
            genres = List(FacetAssertionLimits.MAX_GENRES) {
                FacetGenre(
                    "genre-$it",
                    if (it == 0) "Ж".repeat(FacetAssertionLimits.MAX_GENRE_RAW_TEXT_LENGTH) else "Жанр $it"
                )
            },
            seriesMemberships = List(FacetAssertionLimits.MAX_SERIES_MEMBERSHIPS) {
                FacetSeriesMembership("series-$it", it + 1)
            }
        )

        val encoded = FacetAssertionCodec.toMap(bounded)!!
        val boundedAuthor = requireNotNull(bounded.author)

        assertEquals(bounded, FacetAssertionCodec.fromMap(bounded.documentId, encoded))
        assertNull(
            FacetAssertionCodec.toMap(
                bounded.copy(author = boundedAuthor.copy(aliases = boundedAuthor.aliases + "one-too-many"))
            )
        )
        assertNull(
            FacetAssertionCodec.toMap(
                bounded.copy(genres = bounded.genres + FacetGenre("one-too-many", "Зайвий жанр"))
            )
        )
        assertNull(
            FacetAssertionCodec.toMap(
                bounded.copy(
                    genres = bounded.genres.mapIndexed { index, genre ->
                        if (index == 0) {
                            genre.copy(rawText = "Ж".repeat(FacetAssertionLimits.MAX_GENRE_RAW_TEXT_LENGTH + 1))
                        } else genre
                    }
                )
            )
        )
        assertNull(
            FacetAssertionCodec.toMap(
                bounded.copy(
                    seriesMemberships = bounded.seriesMemberships +
                        FacetSeriesMembership("one-too-many", FacetAssertionLimits.MAX_SERIES_MEMBERSHIPS + 1)
                )
            )
        )
    }

    @Test
    fun `malformed mixed or unbounded documents decode as miss`() {
        val work = workAssertion()
        val valid = FacetAssertionCodec.toMap(work)!!
        val duplicateAliases = mapOf(
            "id" to "author-id",
            "name" to "Автор",
            "aliases" to listOf("Псевдонім", "Псевдонім")
        )
        val duplicateGenres = listOf(
            mapOf("id" to "fantasy", "rawText" to "Фентезі"),
            mapOf("id" to "fantasy", "rawText" to "Фантастика")
        )
        val duplicateSeries = listOf(
            mapOf("seriesId" to "series-id", "position" to 1L),
            mapOf("seriesId" to "series-id", "position" to 2L)
        )
        val malformed = listOf(
            valid - "entityId",
            valid + ("entityId" to " "),
            valid + ("sourceId" to "x".repeat(FacetAssertionLimits.MAX_SOURCE_ID_LENGTH + 1)),
            valid + ("sourceId" to "source~collision"),
            valid + ("genres" to "fantasy"),
            valid + ("genres" to List(FacetAssertionLimits.MAX_GENRES + 1) {
                mapOf("id" to "g$it", "rawText" to "Genre $it")
            }),
            valid + ("genres" to listOf(mapOf("id" to "fantasy"))),
            valid + ("genres" to listOf(mapOf("id" to "fantasy", "rawText" to "   "))),
            valid + ("author" to duplicateAliases),
            valid + ("genres" to duplicateGenres),
            valid + ("seriesMemberships" to duplicateSeries),
            valid + ("narrator" to mapOf("id" to "n", "name" to "Narrator", "aliases" to emptyList<String>())),
            valid + ("unexpected" to "personal"),
            valid + ("updatedAt" to 1.5),
            valid - "author" - "genres" - "seriesMemberships",
        )

        malformed.forEach { assertNull(FacetAssertionCodec.fromMap(work.documentId, it)) }
        assertNull(FacetAssertionCodec.fromMap("w_wrong", valid))

        val edition = editionAssertion()
        val editionMap = FacetAssertionCodec.toMap(edition)!!
        assertNull(FacetAssertionCodec.fromMap(edition.documentId, editionMap + ("durationRef" to "other-edition")))
        assertNull(FacetAssertionCodec.fromMap(edition.documentId, editionMap + ("durationBucket" to "overnight")))
        assertNull(FacetAssertionCodec.fromMap(edition.documentId, editionMap + ("chapterCount" to -1L)))
        assertNull(FacetAssertionCodec.fromMap(edition.documentId, editionMap + ("availabilityTtlSeconds" to 0L)))
    }

    private fun workAssertion() = FacetAssertion.Work(
        workId = "work-id",
        sourceId = "4read",
        author = FacetPerson("author-id", "Автор", listOf("Псевдонім")),
        genres = listOf(FacetGenre("fantasy", "Фентезі")),
        seriesMemberships = listOf(FacetSeriesMembership("series-id", 1)),
        observedAt = 1_000L,
        updatedAt = 10L
    )

    private fun editionAssertion(
        editionId: String = "edition-id",
        workId: String = "work-id"
    ) = FacetAssertion.Edition(
        editionId = editionId,
        workId = workId,
        sourceId = "4read",
        narrator = FacetPerson("narrator-id", "Оповідач"),
        language = "uk",
        durationRef = editionId,
        durationBucket = FacetDurationBucket.UNDER_FIVE_HOURS,
        chapterCount = 10,
        completeness = FacetCompleteness.ABRIDGED,
        availability = FacetAvailability(true, 1_000L, 3_600L),
        observedAt = 1_000L,
        updatedAt = 11L
    )
}
