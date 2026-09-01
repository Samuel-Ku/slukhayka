package com.slukhayka.audiobooks.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure model seam (spec-10 T6 + spec-13 T2): the per-source download policy.
 * Tests the external contract only — which sources are stream-only, and which
 * CDNs need extra download headers. No network.
 *
 * Spec-13 T2: [headersFor] is keyed by SOURCE ID, not URL host — sluhay,
 * sluhayknigi and audiobookmp3 all stream from the SAME `redirectto.cc` CDN
 * but need different `Referer` values, so the source must own its headers.
 */
class DownloadPolicyTest {

    @Test
    fun `lihtar is stream-only - its ToS forbids reproduction`() {
        assertTrue(streamOnlyFor("lihtar"))
    }

    @Test
    fun `download-permitting sources and unknown ones are not stream-only`() {
        // sluhayua included per the T1 verdict: robots.txt has no Disallow, no
        // ToS restriction found, the site tracks downloadedTimes — downloads
        // are intended use. sluhay/sluhayknigi (spec-13): robots open, no
        // download prohibition found in the spike — allowed.
        listOf(
            "4read", "soundbooks", "audiobookmp3", "sluhayua", "sluhay", "sluhayknigi",
            "local", "unknown-source"
        ).forEach { sourceId ->
            assertFalse("$sourceId must allow downloads", streamOnlyFor(sourceId))
        }
    }

    @Test
    fun `audiobookmp3 cdn tracks require the site referer`() {
        val track = "https://9giiu0g54k8c.redirectto.cc/s05/2/6/7/3/8/track-0.mp3"
        assertEquals(
            mapOf("Referer" to "https://audiobook-mp3.com/uk"),
            headersFor("audiobookmp3", track)
        )
    }

    @Test
    fun `sluhay tracks require the sluhay com referer even on the shared cdn`() {
        // Same CDN host as audiobookmp3, different Referer — the seam must be
        // source-aware, never host-aware (spec-13 T2 / T1 spike verdict).
        val track = "https://j3wccg4mgjcw.redirectto.cc/s05/2/6/5/4/4/track-0.mp3"
        assertEquals(
            mapOf("Referer" to "https://sluhay.com/"),
            headersFor("sluhay", track)
        )
        assertEquals(
            mapOf("Referer" to "https://sluhayknigi.com/"),
            headersFor("sluhayknigi", track)
        )
    }

    @Test
    fun `4read tracks require the 4read referer`() {
        val track = "https://s1.reasd.org/5370/01-bunker.mp3"
        assertEquals(
            mapOf("Referer" to "https://4read.org/"),
            headersFor("4read", track)
        )
    }

    @Test
    fun `4read source does not leak its referer to an external archive track`() {
        val track = "https://archive.org/download/example/chapter-01.mp3"
        assertTrue(headersFor("4read", track).isEmpty())
    }

    @Test
    fun `SoundBooks archive tracks require the SoundBooks referer`() {
        val track = "https://arch.sound-books.net/3081/%D0%96%D0%B5%D1%80%D1%82%D0%B2%D0%B0.mp3"
        assertEquals(
            mapOf("Referer" to "https://sound-books.net/"),
            headersFor("soundbooks", track)
        )
    }

    @Test
    fun `SoundBooks referer never leaks to an external archive track`() {
        val track = "https://archive.org/download/example/chapter-01.mp3"
        assertTrue(headersFor("soundbooks", track).isEmpty())
    }

    @Test
    fun `sources that serve plain GETs need no extra headers`() {
        val anyUrl = "https://cdn.example.invalid/s05/1/2/3/track-0.mp3"
        listOf("soundbooks", "sluhayua", "lihtar", "local", "unknown-source").forEach { sourceId ->
            assertTrue("$sourceId must send no headers", headersFor(sourceId, anyUrl).isEmpty())
        }
    }

    @Test
    fun `the referer is source-owned, not url-owned`() {
        // The URL is only a documentation hook today: a blank stream URL on a
        // playerjs source still requires the source Referer (the gate is the
        // source, not the path).
        assertEquals(
            mapOf("Referer" to "https://sluhay.com/"),
            headersFor("sluhay", "")
        )
    }

    @Test
    fun `4read cookie is scoped to its own audio hosts`() {
        val headers = headersFor("4read", "https://s1.reasd.org/5370/01-bunker.mp3", "cf_clearance=local")
        assertEquals("cf_clearance=local", headers["Cookie"])
        assertFalse(headersFor("4read", "https://archive.org/book.mp3", "cf_clearance=local").containsKey("Cookie"))
        assertFalse(headersFor("soundbooks", "https://s1.reasd.org/book.mp3", "cf_clearance=local").containsKey("Cookie"))
    }
}
