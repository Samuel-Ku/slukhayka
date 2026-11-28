package com.slukhayka.audiobooks.data.recommend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contract maximum is 20 000 Works and 8 MiB. The real LibriVox export hits
 * both, so the parser must accept that shape on the JVM, not just the 23-book
 * fixture.
 */
class GraphScaleTest {
    private val now = 1_000_000L

    @Test fun `parses the 20000 work contract maximum`() {
        val count = 20_000
        val width = 22
        val wire = buildString(8 * 1024 * 1024) {
            append("""{"schemaVersion":1,"model":"minishlab/potion-multilingual-128M",""")
            append(""""revision":"${"a".repeat(40)}","catalogSha256":"${"b".repeat(64)}",""")
            append(""""generatedAt":1000000,"expiresAt":604800000,"workKeys":[""")
            for (i in 0 until count) {
                if (i > 0) append(',')
                append("\"k").append(i).append('"')
            }
            append("],\"neighbors\":[")
            for (i in 0 until count) {
                if (i > 0) append(',')
                append('[')
                for (edge in 0 until width) {
                    if (edge > 0) append(',')
                    append('[').append((i + 1 + edge) % count).append(",0.5]")
                }
                append(']')
            }
            append("]}")
        }
        assertTrue("fixture must stay inside the 8 MiB wire bound", wire.toByteArray().size <= 8 * 1024 * 1024)
        val graph = PrecomputedRecommendationGraph.parse(wire, now)
        assertNotNull(graph)
        assertEquals(0.5, graph!!.similarity("k0", "k1")!!, 1e-9)
        assertEquals(0.5, graph.similarity("k19999", "k0")!!, 1e-9)
    }
}
