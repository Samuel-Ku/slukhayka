package com.slukhayka.audiobooks.data.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #387 — the Range-resume decision: a kept partial continues ONLY on a 206
 * whose own Content-Range starts exactly where the partial ends. Every
 * other shape restarts or fails WITHOUT touching the partial, so a stale
 * or corrupt partial can never poison the download.
 */
class DownloadResumePolicyTest {

    @Test
    fun `stable resume temp name has no tmp suffix and keys the track url`() {
        val a = DownloadResumePolicy.resumeTempName("ch-1", "https://x/audio.mp3")
        val b = DownloadResumePolicy.resumeTempName("ch-1", "https://x/audio.mp3")
        val c = DownloadResumePolicy.resumeTempName("ch-1", "https://x/other.mp3")
        assertEquals(a, b)
        assertTrue("different track url -> different partial", a != c)
        assertTrue("never *.tmp", !a.endsWith(".tmp"))
    }

    @Test
    fun `url key is stable and hex`() {
        val k = DownloadResumePolicy.urlKey("https://x/audio.mp3")
        assertEquals(k, DownloadResumePolicy.urlKey("https://x/audio.mp3"))
        assertTrue(k.matches(Regex("[0-9a-f]{16}")))
    }

    @Test
    fun `valid content range parses`() {
        val range = DownloadResumePolicy.parseContentRange("bytes 10485760-83886079/83886080")
        assertEquals(10485760L, range?.startBytes)
        assertEquals(83886080L, range?.totalBytes)
    }

    @Test
    fun `unknown total parses with null total`() {
        val range = DownloadResumePolicy.parseContentRange("bytes 100-199/*")
        assertEquals(100L, range?.startBytes)
        assertNull(range?.totalBytes)
    }

    @Test
    fun `malformed ranges never parse`() {
        assertNull(DownloadResumePolicy.parseContentRange(null))
        assertNull(DownloadResumePolicy.parseContentRange(""))
        assertNull(DownloadResumePolicy.parseContentRange("bytes abc-def/123"))
        assertNull(DownloadResumePolicy.parseContentRange("bytes 200-100/1000"))
        assertNull(DownloadResumePolicy.parseContentRange("bytes 0-99/99"))
        assertNull(DownloadResumePolicy.parseContentRange("items 0-99/1000"))
    }

    @Test
    fun `matching 206 resumes with its total`() {
        val decision = DownloadResumePolicy.decide(
            existingBytes = 10485760L,
            status = 206,
            contentRange = "bytes 10485760-83886079/83886080",
        )
        assertTrue(decision is DownloadResumePolicy.Decision.Resume)
        assertEquals(83886080L, (decision as DownloadResumePolicy.Decision.Resume).totalBytes)
    }

    @Test
    fun `206 with mismatched start restarts`() {
        val decision = DownloadResumePolicy.decide(
            existingBytes = 100L,
            status = 206,
            contentRange = "bytes 0-99/1000",
        )
        assertTrue(decision is DownloadResumePolicy.Decision.Restart)
    }

    @Test
    fun `206 without a usable range restarts`() {
        assertTrue(
            DownloadResumePolicy.decide(100L, 206, null)
                is DownloadResumePolicy.Decision.Restart
        )
        assertTrue(
            DownloadResumePolicy.decide(100L, 206, "bytes nope")
                is DownloadResumePolicy.Decision.Restart
        )
    }

    @Test
    fun `200 restarts - the server ignored the range`() {
        assertTrue(
            DownloadResumePolicy.decide(100L, 200, null)
                is DownloadResumePolicy.Decision.Restart
        )
    }

    @Test
    fun `empty partial never resumes`() {
        assertTrue(
            DownloadResumePolicy.decide(0L, 206, "bytes 0-99/1000")
                is DownloadResumePolicy.Decision.Restart
        )
    }
}
