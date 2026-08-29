package com.slukhayka.audiobooks.data.source

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM seam (spec-15 T4): the one-tap download gate of a «Увесь каталог»
 * card. Any attached non-stream-only source keeps the affordance visible; the
 * download coordinator chooses the first eligible source in capability order.
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
    fun `a download-allowed secondary source keeps the affordance visible`() {
        assertTrue(catalogCardDownloadAllowed(result("soundbooks", "lihtar")))
        assertTrue(catalogCardDownloadAllowed(result("lihtar", "soundbooks")))
    }

    @Test
    fun `a card without sources offers no download`() {
        assertFalse(catalogCardDownloadAllowed(result()))
    }
}
