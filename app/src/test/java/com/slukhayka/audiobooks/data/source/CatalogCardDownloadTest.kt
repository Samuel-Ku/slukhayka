package com.slukhayka.audiobooks.data.source

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM seam (spec-15 T4): the one-tap download gate of a «Увесь каталог»
 * card. The card's primary (first) source decides: a stream-only source
 * (lihtar — ToS forbids reproduction) hides the affordance, exactly as the
 * detail screen hides it for the same book.
 */
class CatalogCardDownloadTest {

    private fun result(vararg sourceIds: String) = GlobalSearchResult(
        title = "Книга",
        author = "Автор",
        mergeKey = "книга|автор",
        sources = sourceIds.map { GlobalSearchSource(it, sourceDisplayName(it), "https://$it.example/x.html") }
    )

    @Test
    fun `stream-only primary source hides the download affordance`() {
        assertFalse(catalogCardDownloadAllowed(result("lihtar")))
    }

    @Test
    fun `download-allowed sources offer the affordance`() {
        assertTrue(catalogCardDownloadAllowed(result("soundbooks")))
        assertTrue(catalogCardDownloadAllowed(result("audiobookmp3")))
        assertTrue(catalogCardDownloadAllowed(result("sluhay")))
        assertTrue(catalogCardDownloadAllowed(result("4read")))
    }

    @Test
    fun `a non-stream-only primary source wins over a stream-only secondary`() {
        // The card plays from (and downloads through) its FIRST source — a
        // lihtar row alongside a download-allowed source must not block it.
        assertTrue(catalogCardDownloadAllowed(result("soundbooks", "lihtar")))
        assertFalse(catalogCardDownloadAllowed(result("lihtar", "soundbooks")))
    }

    @Test
    fun `a card without sources offers no download`() {
        assertFalse(catalogCardDownloadAllowed(result()))
    }
}
