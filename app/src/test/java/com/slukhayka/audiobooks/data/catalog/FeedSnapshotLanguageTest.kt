package com.slukhayka.audiobooks.data.catalog

import com.slukhayka.audiobooks.data.source.SourceBook
import org.junit.Assert.assertEquals
import org.junit.Test

/** Per-card language is a source claim; reloading a snapshot must not erase it. */
class FeedSnapshotLanguageTest {
    @Test
    fun `snapshot reload preserves each language and the full ordered mixed source cards`() {
        // LibriVox is multilingual: a source-wide fallback cannot restore these claims.
        val cards = listOf(
            SourceBook(
                title = "Zeta archive", author = "English author", narrator = "English narrator",
                url = "https://archive.org/details/zeta_language_fixture",
                coverImageUrl = "https://covers.example/zeta.jpg", seriesTitle = "Archive sequence",
                seriesIndex = 7, genre = "Adventure", totalDurationSeconds = 7201L,
                sourceId = "librivox", language = "en"
            ),
            SourceBook(
                title = "Альфа архіву", author = "Український автор", narrator = "Українська оповідачка",
                url = "https://archive.org/details/alpha_language_fixture",
                coverImageUrl = "https://covers.example/alpha.jpg", seriesTitle = "Архівна серія",
                seriesIndex = 2, genre = "Пригоди", totalDurationSeconds = 3605L,
                sourceId = "librivox", language = "uk"
            )
        )

        assertEquals(
            "FEED_SNAPSHOT_CARD_LANGUAGE_FIRST: persistence preserves the complete per-card claims and order",
            cards, FeedSnapshotCodec.decodeBooks(FeedSnapshotCodec.encodeBooks(cards))
        )
    }

    @Test
    fun `legacy snapshot without language keeps it unknown and preserves its remaining payload`() {
        // Literal stored JSON from the old shape: no language key and no claimed fallback.
        val legacy = """[
            {"title":"Legacy card","author":"Legacy author","narrator":"Legacy narrator",
             "url":"https://archive.org/details/legacy_language_fixture",
             "coverImageUrl":"https://covers.example/legacy.jpg","seriesTitle":"Legacy series",
             "seriesIndex":3,"genre":"History","totalDurationSeconds":5403,"sourceId":"librivox"},
            {"title":"Another legacy card","author":"Other author","narrator":"Other narrator",
             "url":"https://example.org/legacy-second","genre":"Poetry",
             "totalDurationSeconds":1801,"sourceId":"librivox"}
        ]"""
        val expected = listOf(
            SourceBook(
                title = "Legacy card", author = "Legacy author", narrator = "Legacy narrator",
                url = "https://archive.org/details/legacy_language_fixture",
                coverImageUrl = "https://covers.example/legacy.jpg", seriesTitle = "Legacy series",
                seriesIndex = 3, genre = "History", totalDurationSeconds = 5403L,
                sourceId = "librivox", language = ""
            ),
            SourceBook(
                title = "Another legacy card", author = "Other author", narrator = "Other narrator",
                url = "https://example.org/legacy-second", coverImageUrl = null, seriesTitle = null,
                seriesIndex = null, genre = "Poetry", totalDurationSeconds = 1801L,
                sourceId = "librivox", language = ""
            )
        )

        assertEquals(expected, FeedSnapshotCodec.decodeBooks(legacy))
    }
}
