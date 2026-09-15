package com.slukhayka.audiobooks.data.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure JVM tests for the two-sided index fold (#823): the write side and
 * the query side share one fold, so what enumeration stores is what the
 * listener's keystrokes become.
 */
class SearchIndexNormalizeTest {

    @Test
    fun `title fold trims parenthetical annotations like the matcher`() {
        assertEquals("кобзар", SearchIndexNormalize.titleField("Кобзар (вибране)"))
    }

    @Test
    fun `person fold lowercases without guessing`() {
        assertEquals("тарас шевченко", SearchIndexNormalize.personField("Тарас Шевченко"))
    }

    @Test
    fun `blank fields fold to empty, never null`() {
        assertEquals("", SearchIndexNormalize.titleField(null))
        assertEquals("", SearchIndexNormalize.titleField("   "))
        assertEquals("", SearchIndexNormalize.personField(null))
    }

    @Test
    fun `query becomes one bare prefix term per token`() {
        assertEquals("кобз*", SearchIndexNormalize.matchQuery("кобз"))
        assertEquals("тарас* шевч*", SearchIndexNormalize.matchQuery("  Тарас   Шевч "))
    }

    @Test
    fun `fts operator words stay quoted exact`() {
        assertEquals("\"not\"", SearchIndexNormalize.matchQuery("not"))
        assertEquals("книга* \"or\"", SearchIndexNormalize.matchQuery("книга or"))
    }

    @Test
    fun `rank exact before prefix before rest`() {
        assertEquals(0, SearchIndexNormalize.rankMatch("кобзар", "тарас шевченко", "кобзар"))
        assertEquals(1, SearchIndexNormalize.rankMatch("кобзареві шляхи", "іван франко", "кобзар"))
        assertEquals(1, SearchIndexNormalize.rankMatch("вечори", "тарас кобзарчук", "тарас"))
        assertEquals(2, SearchIndexNormalize.rankMatch("великий кобзар", "леся українка", "кобзар"))
        assertEquals(2, SearchIndexNormalize.rankMatch("", "тарас шевченко", "кобзар"))
        assertEquals(2, SearchIndexNormalize.rankMatch("кобзар", "тарас шевченко", ""))
    }

    @Test
    fun `blank query matches nothing`() {
        assertNull(SearchIndexNormalize.matchQuery(""))
        assertNull(SearchIndexNormalize.matchQuery("   "))
        assertNull(SearchIndexNormalize.matchQuery("—"))
    }
}
