package com.slukhayka.audiobooks.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1751 — a YouTube watch URL has no extension; declaring it `audio/mpeg`
 * made the receiver treat the resolved M4A stream as the wrong family. The
 * resolver prefers progressive M4A, so the cast playlist says `audio/mp4`
 * while plain URLs keep their extension-based answer — and the declaration is
 * not a hope: [CastUpstream] resolves the URL first and refuses a stream of
 * another family, which is what [declaresFamily] decides.
 */
class CastMimeTypesTest {

    @Test
    fun `a youtube watch url declares the audio family the resolver prefers`() {
        assertEquals(
            "audio/mp4",
            CastMimeTypes.forCast("https://www.youtube.com/watch?v=ozaZXk5Qcwc")
        )
    }

    @Test
    fun `plain urls keep their extension based mime`() {
        assertEquals("audio/mpeg", CastMimeTypes.forCast("https://example.org/a.mp3?token=1"))
        assertEquals("audio/mp4", CastMimeTypes.forCast("https://example.org/a.m4b"))
        assertEquals("audio/ogg", CastMimeTypes.forCast("https://example.org/a.opus#t=1"))
        assertEquals("audio/flac", CastMimeTypes.forCast("https://example.org/a.flac"))
    }

    @Test
    fun `only the declared family may be served under the declaration`() {
        assertTrue(CastMimeTypes.declaresFamily("audio/mp4", "audio/mp4"))
        assertTrue("a codecs parameter does not change the container", CastMimeTypes.declaresFamily("audio/mp4", "audio/mp4; codecs=mp4a.40.2"))
        assertFalse("WebM is a different family", CastMimeTypes.declaresFamily("audio/mp4", "audio/webm"))
        assertFalse(
            "an unknown family is not evidence of MP4",
            CastMimeTypes.declaresFamily("audio/mp4", null)
        )
        assertFalse(
            "a blank family is not evidence of MP4 either",
            CastMimeTypes.declaresFamily("audio/mp4", "  ")
        )
    }
}
