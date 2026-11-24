package com.slukhayka.audiobooks.data.collective

import com.slukhayka.audiobooks.data.source.SourceAccessMode
import com.slukhayka.audiobooks.data.source.SourceFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #523 — the collective blocks cover the DIRECT Ukrainian catalogue sources,
 * in registry order; a browser-gated, scam, non-Ukrainian or local source is
 * never a block source.
 */
class CollectiveBlockSourcesTest {

    private fun facts(
        id: String,
        accessMode: SourceAccessMode = SourceAccessMode.DIRECT,
        language: String = "uk",
        scam: Boolean = false,
        order: Int = 0
    ) = SourceFacts(
        id = id,
        displayName = id,
        homeUrl = "https://$id.example",
        contentLanguage = language,
        accessMode = accessMode,
        order = order,
        scam = scam
    )

    @Test
    fun `only direct non-scam Ukrainian sources qualify`() {
        val selected = collectiveBlockSources(
            listOf(
                facts("soundbooks", order = 1),
                facts("sluhayua", order = 2),
                facts("local", order = -1),
                facts("4read", accessMode = SourceAccessMode.BROWSER, scam = true, order = 0),
                facts("librivox", language = "en", order = 5),
                facts("ukrainianaudiobooks", accessMode = SourceAccessMode.BROWSER, order = 9)
            )
        ).map { it.id }

        assertEquals(listOf("soundbooks", "sluhayua"), selected)
    }

    @Test
    fun `the registry selection is used by default and is ordered`() {
        val selected = collectiveBlockSources()

        assertTrue("the first slice has direct Ukrainian sources", selected.isNotEmpty())
        assertTrue(selected.all { it.accessMode == SourceAccessMode.DIRECT })
        assertTrue(selected.all { it.contentLanguage == "uk" })
        assertFalse(selected.any { it.scam })
        assertFalse(selected.any { it.id == "local" })
        assertEquals(selected.map { it.order }, selected.map { it.order }.sorted())
    }

    @Test
    fun `the new-arrivals block key names the source and its canonical provenance`() {
        // #1238 — the context is the registry home without scheme/slash, so the
        // identity is stable across http/https spelling and trailing slashes.
        assertEquals("soundbooks|NEW_ARRIVALS|sound-books.net", newArrivalsBlockKey("soundbooks"))
        assertEquals(CollectiveBlockKind.NEW_ARRIVALS, parseCollectiveBlockKey(newArrivalsBlockKey("soundbooks"))!!.kind)
        assertEquals("sound-books.net", parseCollectiveBlockKey(newArrivalsBlockKey("soundbooks"))!!.provenanceContext)
    }

    @Test
    fun `two related collections of one source get distinct identities`() {
        val a = collectiveGenreBlockKey("soundbooks", "/collections/do-a")
        val b = collectiveGenreBlockKey("soundbooks", "/collections/do-b")

        assertEquals(false, a == b)
        assertEquals("soundbooks", parseCollectiveBlockKey(a)!!.sourceId)
        assertEquals(
            "sound-books.net/collections/do-a",
            parseCollectiveBlockKey(a)!!.provenanceContext
        )
    }
}
