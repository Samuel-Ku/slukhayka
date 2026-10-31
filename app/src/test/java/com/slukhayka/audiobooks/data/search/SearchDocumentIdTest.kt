package com.slukhayka.audiobooks.data.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1735 — the Firestore document id of a shared search entry: a stable
 * SHA-256 hex of the normalized query, so the publicly readable
 * `search_results` collection never spells out listeners' search history.
 * Pinned here: stability across normalization, separation of different
 * queries, and a valid Firestore id for text that is illegal in a document
 * path. The seam round-trip lives in [SearchCacheTest].
 */
class SearchDocumentIdTest {

    private val sha256Hex = Regex("[0-9a-f]{64}")

    @Test
    fun `the document id is a stable sha-256 hex of the normalized query`() {
        val id = SearchDocumentId.of("  Шевченко  ")!!

        assertTrue("not a sha-256 hex: $id", sha256Hex.matches(id))
        assertEquals(id, SearchDocumentId.of("шевченко"))
        assertEquals(id, SearchDocumentId.of("ШЕВЧЕНКО"))
        assertEquals(id, SearchDocumentId.of("   Шевченко   "))
    }

    @Test
    fun `the derivation is pinned - a namespace change re-keys every entry`() {
        // The golden vector includes the version namespace, so an accidental
        // edit to the preimage cannot pass: bumping the namespace is a
        // deliberate re-key, not a silent second cache generation.
        assertEquals(
            "c5d1fd549c1ef7fbc5f3adf409de8eab8ecd254c8bf2a69f8f952f63a7302d6a",
            SearchDocumentId.of("Шевченко")
        )
    }

    @Test
    fun `different queries get different document ids`() {
        assertNotEquals(SearchDocumentId.of("Шевченко"), SearchDocumentId.of("Гаррі Поттер"))
        assertNotEquals(SearchDocumentId.of("кобзар"), SearchDocumentId.of("кобзар 2"))
        assertNotEquals(SearchDocumentId.of("шевченко"), SearchDocumentId.of("шевченко!"))
    }

    @Test
    fun `a blank query has no document id`() {
        assertNull(SearchDocumentId.of(""))
        assertNull(SearchDocumentId.of("   \n\t "))
    }

    @Test
    fun `the raw query never appears in the document id`() {
        val id = SearchDocumentId.of("cafe")!!

        assertNotEquals("cafe", id)
        assertFalse("the query leaked into the id: $id", id.contains("cafe"))
    }

    @Test
    fun `a query with a slash newline or emoji yields a valid Firestore id`() {
        val queries = listOf(
            "Гаррі Поттер / 2",
            "Тарас\nШевченко",
            "Кобзар 🎧📚",
            "  /  ",
            "..",
            "__name__"
        )

        for (query in queries) {
            val id = SearchDocumentId.of(query)
            assertTrue("no id for ${query.replace("\n", "\\n")}", id != null)
            val value = id!!
            assertTrue("not a sha-256 hex for ${query.replace("\n", "\\n")}: $value", sha256Hex.matches(value))
            // Firestore rejects an id containing '/', and a reserved '.'/'..'
            // or '__.*__' shape would be refused too; hex cannot produce any
            // of those, and this pins that the encoding stays hex.
            assertFalse("a Firestore id must not contain '/': $value", value.contains('/'))
            assertFalse("a Firestore id must not be reserved: $value", value == "." || value == "..")
            assertFalse("a Firestore id must not be whitespace: $value", value.any { it.isWhitespace() })
        }
    }
}
