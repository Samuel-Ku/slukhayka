package com.slukhayka.audiobooks.data.recommend

import org.junit.Assert.assertEquals
import org.junit.Test

class RecommendationEvalCatalogTest {
    @Test
    fun `two recordings of one real title do not inflate Work count`() {
        val records = listOf(
            mapOf("identifier" to "carmilla_v2", "title" to "Carmilla (version 2)", "creator" to "Joseph Sheridan Le Fanu", "language" to "eng"),
            mapOf("identifier" to "carmilla_v1", "title" to "Carmilla", "creator" to "Joseph Sheridan Le Fanu", "language" to "eng")
        )
        val catalog = RecommendationEvalCatalog.fromRecords(records)
        assertEquals(1, catalog.works.size)
        assertEquals("Carmilla", catalog.works.single().title)
        assertEquals(1, catalog.duplicateEditions)
        assertEquals(listOf("https://archive.org/details/carmilla_v1", "https://archive.org/details/carmilla_v2"),
            catalog.sourceUrls.getValue(catalog.works.single().id))
    }
    @Test
    fun `source spelling and recording labels cannot split a known authored Work`() {
        val catalog = RecommendationEvalCatalog.fromRecords(listOf(
            mapOf("identifier" to "frankenstein_a", "title" to "Frankenstein, Version 5", "creator" to "Mary W. Shelley", "language" to "eng"),
            mapOf("identifier" to "frankenstein_b", "title" to "Frankenstein (Edition 1831)", "creator" to "Mary Wollstonecraft Shelley", "language" to "eng"),
            mapOf("identifier" to "frankenstein_c", "title" to "Frankenstein; or, The Modern Prometheus (1818)", "creator" to "Mary Shelley", "language" to "eng")
        ))
        assertEquals(1, catalog.works.size)
        assertEquals("Frankenstein", catalog.works.single().title)
        assertEquals(2, catalog.duplicateEditions)
    }

    @Test
    fun `an explicitly initialed creator and its supplied full name share the authored Work`() {
        val catalog = RecommendationEvalCatalog.fromRecords(listOf(
            mapOf("identifier" to "time_machine_a", "title" to "The Time Machine", "creator" to "H.G. Wells", "language" to "eng"),
            mapOf("identifier" to "time_machine_b", "title" to "The Time Machine", "creator" to "Herbert George Wells", "language" to "eng")
        ))
        assertEquals(1, catalog.works.size)
    }

    @Test
    fun `ambiguous initials cannot assign one author's recording to another`() {
        val catalog = RecommendationEvalCatalog.fromRecords(listOf(
            mapOf("identifier" to "a", "title" to "Selected Poems", "creator" to "John Smith", "language" to "eng"),
            mapOf("identifier" to "b", "title" to "Selected Poems", "creator" to "James Smith", "language" to "eng"),
            mapOf("identifier" to "c", "title" to "Selected Poems", "creator" to "J. Smith", "language" to "eng")
        ))
        assertEquals(2, catalog.works.size)
        assertEquals(1, catalog.missingIdentity)
        assertEquals(0, catalog.duplicateEditions)
    }

    @Test
    fun `a coauthored title cannot absorb a solo author's different Work`() {
        val catalog = RecommendationEvalCatalog.fromRecords(listOf(
            mapOf("identifier" to "a", "title" to "Poems", "creator" to listOf("John Smith", "Mary Jones"), "language" to "eng"),
            mapOf("identifier" to "b", "title" to "Poems", "creator" to "John Smith", "language" to "eng")
        ))
        assertEquals(2, catalog.works.size)
    }
    @Test
    fun `placeholder author names do not prove authored Work identities`() {
        val catalog = RecommendationEvalCatalog.fromRecords(listOf(
            mapOf("identifier" to "a", "title" to "Tales", "creator" to "Unknown Author", "language" to "eng"),
            mapOf("identifier" to "b", "title" to "Poems", "creator" to "Anonymous, 1800-1900", "language" to "eng")
        ))
        assertEquals(0, catalog.works.size)
        assertEquals(2, catalog.missingIdentity)
    }

}
