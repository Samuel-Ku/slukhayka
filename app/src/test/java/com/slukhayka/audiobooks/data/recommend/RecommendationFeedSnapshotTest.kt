package com.slukhayka.audiobooks.data.recommend

import com.slukhayka.audiobooks.data.source.SourceGateParams
import com.slukhayka.audiobooks.data.source.SourceRequestGate
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecommendationFeedSnapshotTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun page(total: Int, start: Int, ids: List<String>): String =
        """{"response":{"numFound":$total,"start":$start,"docs":[${ids.joinToString(",") { "{\"identifier\":\"$it\"}" }}]}}"""
    private fun gate() = SourceRequestGate(SourceGateParams(jitterMinMs = 0, jitterMaxMs = 0))

    @Test
    fun `a repeated page boundary is rejected before it can poison resume`() = runBlocking {
        val dir = temporary.newFolder()
        val first = (0 until 500).map { "id%04d".format(it) }
        try {
            RecommendationFeedSnapshot.acquire(dir, gate()) { url ->
                if ("page=1&" in url) page(501, 0, first) else page(501, 500, listOf("id0499"))
            }
            error("Repeated boundary must fail")
        } catch (_: IllegalArgumentException) {
            assertTrue(File(dir, "page-000.json.gz").exists())
            assertFalse("Bad page must not be frozen", File(dir, "page-001.json.gz").exists())
        }
    }
    @Test(expected = IllegalArgumentException::class)
    fun `a tampered frozen manifest cannot be silently accepted on resume`() = runBlocking {
        val dir = temporary.newFolder()
        val first = (0 until 500).map { "id%04d".format(it) }
        RecommendationFeedSnapshot.acquire(dir, gate()) { url ->
            if ("page=1&" in url) page(501, 0, first) else page(501, 500, listOf("id0500"))
        }
        val file = File(dir, "manifest.properties")
        file.writeText(file.readText().replace("totalRecords=501", "totalRecords=999"))
        RecommendationFeedSnapshot.acquire(dir, gate()) { error("Resume must never fetch") }
        Unit
    }

    @Test
    fun `changed total or skipped page cannot freeze a mixed snapshot`() {
        for (bad in listOf(page(502, 500, listOf("id0500", "id0501")), page(501, 1000, listOf("id0500")))) {
            val dir = temporary.newFolder()
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { RecommendationFeedSnapshot.acquire(dir, gate()) { url ->
                    if ("page=1&" in url) page(501, 0, (0 until 500).map { "id%04d".format(it) }) else bad
                } }
            }
            assertFalse(File(dir, "page-001.json.gz").exists())
            assertFalse(File(dir, "manifest.properties").exists())
        }
    }
    @Test
    fun `reordered or malformed response cannot become a frozen page`() {
        val dir = temporary.newFolder()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { RecommendationFeedSnapshot.acquire(dir, gate()) { page(2, 0, listOf("b", "a")) } }
        }
        assertFalse(File(dir, "page-000.json.gz").exists())
        assertThrows(IllegalStateException::class.java) {
            runBlocking { RecommendationFeedSnapshot.acquire(dir, gate()) { "{\"response\":{}}" } }
        }
        assertFalse(File(dir, "manifest.properties").exists())
    }
    @Test
    fun `resume skips completed pages and rejects altered provenance`() {
        val dir = temporary.newFolder()
        assertThrows(java.io.IOException::class.java) {
            runBlocking { RecommendationFeedSnapshot.acquire(dir, gate()) { url ->
                if ("page=1&" in url) page(501, 0, (0 until 500).map { "id%04d".format(it) })
                else throw java.io.IOException("interrupted")
            } }
        }
        val provenance = File(dir, "page-000.json.gz.url")
        val correct = provenance.readText()
        provenance.writeText("https://different.example/catalog")
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { RecommendationFeedSnapshot.acquire(dir, gate()) { page(501, 500, listOf("id0500")) } }
        }
        provenance.writeText(correct)
        var calls = 0
        val resumed = runBlocking { RecommendationFeedSnapshot.acquire(dir, gate()) { url ->
            assertTrue("Completed page must not be requested again", "page=2&" in url)
            calls++
            page(501, 500, listOf("id0500"))
        } }
        assertEquals(1, calls)
        assertEquals(501, RecommendationFeedSnapshot.records(resumed).size)
        assertEquals(501, RecommendationFeedSnapshot.load(dir).totalRecords)
    }
    @Test
    fun `ten thousand boundary resumes by strict keyset without truncation`() = runBlocking {
        val dir = temporary.newFolder()
        val fastTestGate = SourceRequestGate(SourceGateParams(bucketCapacity = 30, backgroundMinTokens = 1, jitterMinMs = 0, jitterMaxMs = 0))
        var request = 0
        val snapshot = RecommendationFeedSnapshot.acquire(dir, fastTestGate) { url ->
            val offset = request++ * 500
            if (offset == 10_000) {
                val decoded = java.net.URLDecoder.decode(url, "UTF-8")
                assertTrue(decoded.contains("identifier:[id09999 TO *] AND NOT identifier:id09999"))
                assertTrue(url.contains("page=1&"))
                page(1, 0, listOf("id10000"))
            } else page(10_001, offset, (offset until offset + 500).map { "id%05d".format(it) })
        }
        assertEquals(21, request)
        assertEquals(10_001, RecommendationFeedSnapshot.records(snapshot).size)
        assertEquals(10_001, RecommendationFeedSnapshot.load(dir).totalRecords)
    }

}
