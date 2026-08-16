package com.example.data.universe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Spec-26 T5 — the Firestore document codec for a [UniverseResolution]: the
 * shape stored in the shared base is pure JVM and round-trips, and a corrupt
 * document decodes to null (a miss, never a crash).
 */
class SharedResolutionCodecTest {

    private val resolution = UniverseResolution(
        universe = UniverseList(
            id = "wd:Q11835640",
            name = "Відьмак",
            series = listOf(
                UniverseSeries(title = "Відьмак", urls = listOf("https://4read.org/xfsearch/cikl/vidmak/")),
                UniverseSeries(title = "Останнє бажання")
            )
        ),
        matchedSeries = UniverseSeries(title = "Останнє бажання"),
        position = 2
    )

    @Test
    fun `a resolution round-trips through the document shape`() {
        val decoded = SharedResolutionCodec.fromMap(SharedResolutionCodec.toMap(resolution))!!

        assertEquals("wd:Q11835640", decoded.universe.id)
        assertEquals("Відьмак", decoded.universe.name)
        assertEquals(listOf("Відьмак", "Останнє бажання"), decoded.universe.series.map { it.title })
        // The url survives on the series that carries one; the other stays bare.
        assertEquals(listOf("https://4read.org/xfsearch/cikl/vidmak/"), decoded.universe.series[0].urls)
        assertEquals(emptyList<String>(), decoded.universe.series[1].urls)
        assertEquals("Останнє бажання", decoded.matchedSeries.title)
        assertEquals(2, decoded.position)
    }

    @Test
    fun `provenance rides the document and reads ignore it`() {
        // Spec-26 T6: the write shape carries the provenance fields; the read
        // shape is unchanged — fromMap decodes the same resolution and never
        // needs (nor sees) the provenance.
        val provenance = ResolutionProvenance(
            source = ResolutionProvenance.SOURCE_WIKIDATA,
            authorVerified = true,
            resolvedAt = 1_234_567L
        )
        val map = SharedResolutionCodec.toMapWithProvenance(resolution, provenance)

        assertEquals(ResolutionProvenance.SOURCE_WIKIDATA, map["source"])
        assertEquals(true, map["authorVerified"])
        assertEquals(1_234_567L, map["resolvedAt"])

        val decoded = SharedResolutionCodec.fromMap(map)!!
        assertEquals("wd:Q11835640", decoded.universe.id)
        assertEquals("Відьмак", decoded.universe.name)
        assertEquals(2, decoded.position)
    }

    @Test
    fun `a missing required field decodes to null`() {
        val map = SharedResolutionCodec.toMap(resolution).toMutableMap()

        map.remove("universeId")
        assertNull(SharedResolutionCodec.fromMap(map))
        map["universeId"] = "wd:Q11835640"

        map.remove("series")
        assertNull(SharedResolutionCodec.fromMap(map))
        map["series"] = listOf(mapOf("title" to "Відьмак"))

        map.remove("position")
        assertNull(SharedResolutionCodec.fromMap(map))
    }

    @Test
    fun `a mistyped field decodes to null`() {
        val map = SharedResolutionCodec.toMap(resolution).toMutableMap()

        map["position"] = "два"
        assertNull(SharedResolutionCodec.fromMap(map))
    }

    @Test
    fun `an empty chain or an out-of-range position decodes to null`() {
        // Empty chain.
        val empty = SharedResolutionCodec.toMap(resolution).toMutableMap()
        empty["series"] = emptyList<Map<String, Any>>()
        assertNull(SharedResolutionCodec.fromMap(empty))

        // Position beyond the chain (and below 1).
        val over = SharedResolutionCodec.toMap(resolution).toMutableMap()
        over["position"] = 3L
        assertNull(SharedResolutionCodec.fromMap(over))
        val under = SharedResolutionCodec.toMap(resolution).toMutableMap()
        under["position"] = 0L
        assertNull(SharedResolutionCodec.fromMap(under))
    }
}
