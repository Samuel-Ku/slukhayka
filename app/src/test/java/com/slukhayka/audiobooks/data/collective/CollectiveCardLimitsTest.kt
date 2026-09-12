package com.slukhayka.audiobooks.data.collective

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #522 — the collective-card acceptance bounds: only a DIRECT, non-scam,
 * Ukrainian catalogue source whose id matches the URL, with every public
 * field inside its bound, may be published; anything else is rejected whole.
 */
class CollectiveCardLimitsTest {

    private fun card(
        sourceId: String = "soundbooks",
        sourceUrl: String = "https://sound-books.net/kobzar",
        title: String = "Кобзар",
        author: String = "Тарас Шевченко",
        narrator: String = "Диктор",
        language: String = "uk",
        coverUrl: String? = null,
        seriesTitle: String? = null,
        durationSeconds: Long? = null,
        chapterCount: Int? = null,
        observedAt: Long = 1_700_000_000_000L
    ) = CollectiveCardPublication(
        sourceId = sourceId,
        sourceUrl = sourceUrl,
        title = title,
        author = author,
        narrator = narrator,
        language = language,
        coverUrl = coverUrl,
        seriesTitle = seriesTitle,
        durationSeconds = durationSeconds,
        chapterCount = chapterCount,
        observedAt = observedAt
    )

    @Test
    fun `a verified direct Ukrainian card is publishable`() {
        assertTrue(CollectiveCardLimits.isPublishable(card()))
        assertTrue(
            CollectiveCardLimits.isPublishable(
                card(sourceId = "sluhayua", sourceUrl = "https://sluhay.com.ua/book")
            )
        )
        assertTrue(
            CollectiveCardLimits.isPublishable(
                card(sourceId = "lihtar", sourceUrl = "https://lihtar.in.ua/book")
            )
        )
    }

    @Test
    fun `the card's source id must match its own URL`() {
        assertFalse(CollectiveCardLimits.isPublishable(card(sourceId = "sluhayua")))
    }

    @Test
    fun `a scam or browser source is never published`() {
        // 4read is a scam: never a collective contribution.
        assertFalse(
            CollectiveCardLimits.isPublishable(
                card(sourceId = "4read", sourceUrl = "https://4read.org/book")
            )
        )
        // A browser-gated source is not a DIRECT source.
        assertFalse(
            CollectiveCardLimits.isPublishable(
                card(sourceId = "ukrainianaudiobooks", sourceUrl = "https://ukrainianaudiobooks.com/book")
            )
        )
    }

    @Test
    fun `a non-Ukrainian catalogue source is out of the first slice`() {
        assertFalse(
            CollectiveCardLimits.isPublishable(
                card(
                    sourceId = "librivox",
                    sourceUrl = "https://librivox.org/emma",
                    title = "Emma",
                    author = "Jane Austen",
                    language = "en"
                )
            )
        )
    }

    @Test
    fun `unknown or foreign language claims are rejected`() {
        assertTrue("an unknown language is honest", CollectiveCardLimits.isPublishable(card(language = "")))
        assertFalse(CollectiveCardLimits.isPublishable(card(language = "en")))
        assertFalse(CollectiveCardLimits.isPublishable(card(language = "x".repeat(20))))
    }

    @Test
    fun `overlong or blank fields reject the whole card`() {
        assertFalse(CollectiveCardLimits.isPublishable(card(title = "")))
        assertFalse(CollectiveCardLimits.isPublishable(card(author = "")))
        assertFalse(CollectiveCardLimits.isPublishable(card(title = "т".repeat(CollectiveCardLimits.MAX_TITLE + 1))))
        assertFalse(CollectiveCardLimits.isPublishable(card(narrator = "н".repeat(CollectiveCardLimits.MAX_NARRATOR + 1))))
        assertFalse(
            CollectiveCardLimits.isPublishable(
                card(sourceUrl = "https://sound-books.net/" + "u".repeat(CollectiveCardLimits.MAX_URL))
            )
        )
        assertFalse(
            CollectiveCardLimits.isPublishable(
                card(seriesTitle = "с".repeat(CollectiveCardLimits.MAX_SERIES_TITLE + 1))
            )
        )
    }

    @Test
    fun `impossible numbers and timestamps are rejected`() {
        assertFalse(CollectiveCardLimits.isPublishable(card(durationSeconds = -1L)))
        assertFalse(CollectiveCardLimits.isPublishable(card(chapterCount = -1)))
        assertFalse(CollectiveCardLimits.isPublishable(card(observedAt = 0L)))
    }

    @Test
    fun `the merge key is the one Work identity`() {
        assertTrue(card().mergeKey.isNotBlank())
    }
}
