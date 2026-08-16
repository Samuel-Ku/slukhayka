package com.example.data.universe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Spec-25 T2 (#173) — pure JVM fixture tests for [WikidataParser]: the two
 * API shapes (wbsearchentities hits, wbgetentities claims+labels) map to
 * typed facts; absent facts and malformed JSON contribute nothing.
 */
class WikidataParserTest {

    private val languages = listOf("uk", "ru", "en")

    // ---------------------------------------------------------------------
    // Search hits
    // ---------------------------------------------------------------------

    @Test
    fun `search hits extract the ids in api order`() {
        val json = """{"search":[{"id":"Q1","label":"A"},{"id":"Q2","label":"B"},{"id":"Q3"}]}"""
        assertEquals(listOf("Q1", "Q2", "Q3"), WikidataParser.searchHitIds(json))
    }

    @Test
    fun `an empty search and malformed json yield no hits`() {
        assertEquals(emptyList<String>(), WikidataParser.searchHitIds("""{"search":[]}"""))
        assertEquals(emptyList<String>(), WikidataParser.searchHitIds("not json"))
        assertEquals(emptyList<String>(), WikidataParser.searchHitIds("""{"search":"not-a-list"}"""))
    }

    // ---------------------------------------------------------------------
    // CirrusSearch hits (spec-26 T2 — the author-narrowed work search)
    // ---------------------------------------------------------------------

    @Test
    fun `cirrus hits extract the ids from query search`() {
        val json = """{"query":{"search":[{"title":"Q1"},{"title":"Q2"}]}}"""
        assertEquals(listOf("Q1", "Q2"), WikidataParser.cirrusHitIds(json))
    }

    @Test
    fun `an empty cirrus search and malformed json yield no hits`() {
        assertEquals(emptyList<String>(), WikidataParser.cirrusHitIds("""{"query":{"search":[]}}"""))
        assertEquals(emptyList<String>(), WikidataParser.cirrusHitIds("""{"query":{}}"""))
        assertEquals(emptyList<String>(), WikidataParser.cirrusHitIds("not json"))
        assertEquals(emptyList<String>(), WikidataParser.cirrusHitIds("""{"query":{"search":"not-a-list"}}"""))
    }

    // ---------------------------------------------------------------------
    // Claims: P50 (author), P179 (series), P155/P156 (chain)
    // ---------------------------------------------------------------------

    private val claimsJson = """
        {
          "entities": {
            "Q1": {
              "labels": {"en": {"language": "en", "value": "A Little Hatred"}},
              "claims": {
                "P50": [
                  {"mainsnak": {"snaktype": "value", "property": "P50", "datavalue": {"value": {"id": "Q10"}}}}
                ],
                "P179": [
                  {"mainsnak": {"snaktype": "value", "property": "P179", "datavalue": {"value": {"id": "Q100"}}}}
                ],
                "P155": [
                  {"mainsnak": {"snaktype": "value", "property": "P155", "datavalue": {"value": {"id": "Q101"}}}}
                ],
                "P156": [
                  {"mainsnak": {"snaktype": "somevalue"}}
                ]
              }
            }
          }
        }
    """.trimIndent()

    @Test
    fun `claims extract the author series and chain entity ids`() {
        assertEquals(listOf("Q10"), WikidataParser.authorIds(claimsJson, "Q1"))
        assertEquals(listOf("Q100"), WikidataParser.seriesIds(claimsJson, "Q1"))
        assertEquals(listOf("Q101"), WikidataParser.followsIds(claimsJson, "Q1"))
        // The somevalue statement (no datavalue) contributes nothing.
        assertEquals(emptyList<String>(), WikidataParser.followedByIds(claimsJson, "Q1"))
    }

    @Test
    fun `a claim of another entity or an absent property contributes nothing`() {
        assertEquals(emptyList<String>(), WikidataParser.authorIds(claimsJson, "Q2"))
        assertEquals(emptyList<String>(), WikidataParser.followsIds(claimsJson, "Q2"))
        assertEquals(emptyList<String>(), WikidataParser.authorIds("not json", "Q1"))
    }

    @Test
    fun `edition of main subject and instance of claims extract their ids`() {
        val json = """
            {"entities":{"Q1":{"claims":{
              "P629":[{"mainsnak":{"snaktype":"value","datavalue":{"value":{"id":"Q2"}}}}],
              "P921":[{"mainsnak":{"snaktype":"value","datavalue":{"value":{"id":"Q3"}}}}],
              "P31":[{"mainsnak":{"snaktype":"value","datavalue":{"value":{"id":"Q277759"}}}}]
            }}}}
        """.trimIndent()

        assertEquals(listOf("Q2"), WikidataParser.editionOfIds(json, "Q1"))
        assertEquals(listOf("Q3"), WikidataParser.mainSubjectIds(json, "Q1"))
        assertEquals(listOf("Q277759"), WikidataParser.instanceOfIds(json, "Q1"))
        // Absent properties and other entities contribute nothing.
        assertEquals(emptyList<String>(), WikidataParser.editionOfIds(json, "Q9"))
        assertEquals(emptyList<String>(), WikidataParser.mainSubjectIds(claimsJson, "Q1"))
    }

    // ---------------------------------------------------------------------
    // Labels
    // ---------------------------------------------------------------------

    @Test
    fun `the label prefers the languages in order`() {
        val all = """{"entities":{"Q1":{"labels":{"en":{"value":"A Little Hatred"},"ru":{"value":"Немного ненависти"},"uk":{"value":"Трохи ненависті"}}}}}"""
        assertEquals("Трохи ненависті", WikidataParser.label(all, "Q1", languages))
        assertEquals("Трохи ненависті", WikidataParser.label(all, "Q1", listOf("uk")))

        // A missing uk label falls through to the next language.
        val noUk = """{"entities":{"Q1":{"labels":{"en":{"value":"A Little Hatred"},"ru":{"value":"Немного ненависти"}}}}}"""
        assertEquals("Немного ненависти", WikidataParser.label(noUk, "Q1", languages))
        assertEquals("A Little Hatred", WikidataParser.label(noUk, "Q1", listOf("en")))
    }

    // ---------------------------------------------------------------------
    // Publication year (spec-26 T7 — the P577 age signal of the tier rule)
    // ---------------------------------------------------------------------

    @Test
    fun `the P577 publication year extracts the calendar year`() {
        val yearPrecision = """{"entities":{"Q1":{"claims":{
          "P577":[{"mainsnak":{"snaktype":"value","datavalue":{"value":{"time":"+2021-00-00T00:00:00Z","precision":9}}}}]
        }}}}""".trimIndent()
        assertEquals(2021, WikidataParser.publicationYear(yearPrecision, "Q1"))

        // A day-precision time yields the same calendar year.
        val dayPrecision = """{"entities":{"Q1":{"claims":{
          "P577":[{"mainsnak":{"snaktype":"value","datavalue":{"value":{"time":"+2021-05-13T00:00:00Z"}}}}]
        }}}}""".trimIndent()
        assertEquals(2021, WikidataParser.publicationYear(dayPrecision, "Q1"))

        // A BCE time parses the year digits.
        val bce = """{"entities":{"Q1":{"claims":{
          "P577":[{"mainsnak":{"snaktype":"value","datavalue":{"value":{"time":"-0455-00-00T00:00:00Z"}}}}]
        }}}}""".trimIndent()
        assertEquals(455, WikidataParser.publicationYear(bce, "Q1"))
    }

    @Test
    fun `a missing or malformed P577 contributes no year`() {
        val none = """{"entities":{"Q1":{"claims":{}}}}""".trimIndent()
        assertNull(WikidataParser.publicationYear(none, "Q1"))

        // An unknown value (somevalue — no datavalue) and a malformed time.
        val someValue = """{"entities":{"Q1":{"claims":{
          "P577":[{"mainsnak":{"snaktype":"somevalue"}}]
        }}}}""".trimIndent()
        assertNull(WikidataParser.publicationYear(someValue, "Q1"))
        val badTime = """{"entities":{"Q1":{"claims":{
          "P577":[{"mainsnak":{"snaktype":"value","datavalue":{"value":{"time":"not-a-time"}}}}]
        }}}}""".trimIndent()
        assertNull(WikidataParser.publicationYear(badTime, "Q1"))

        // Another entity and malformed json.
        assertNull(WikidataParser.publicationYear(none, "Q9"))
        assertNull(WikidataParser.publicationYear("not json", "Q1"))
    }

    @Test
    fun `an entity without labels yields null`() {
        val json = """{"entities":{"Q1":{"labels":{}}}}"""
        assertNull(WikidataParser.label(json, "Q1", languages))
        assertNull(WikidataParser.label("not json", "Q1", languages))
    }
}
