package com.example.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure model seam (spec-10 T6): the per-source download policy. Tests the
 * external contract only — which sources are stream-only, and which CDNs need
 * extra download headers. No network.
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
        // are intended use.
        listOf("4read", "soundbooks", "audiobookmp3", "sluhayua", "local", "unknown-source").forEach { sourceId ->
            assertFalse("$sourceId must allow downloads", streamOnlyFor(sourceId))
        }
    }

    @Test
    fun `audiobookmp3 cdn tracks require the site referer`() {
        val track = "https://9giiu0g54k8c.redirectto.cc/s05/2/6/7/3/8/track-0.mp3"
        assertEquals(
            mapOf("Referer" to "https://audiobook-mp3.com/uk"),
            downloadHeadersFor(track)
        )
    }

    @Test
    fun `other source cdns need no extra headers`() {
        assertTrue(downloadHeadersFor("https://arch.sound-books.net/4111/theme.mp3").isEmpty())
        assertTrue(downloadHeadersFor("https://4read.org/uploads/audio/1.mp3").isEmpty())
        assertTrue(downloadHeadersFor("https://web.lihtar.in.ua/audio/library/1/x.mp3").isEmpty())
        assertTrue(downloadHeadersFor("").isEmpty())
    }
}
