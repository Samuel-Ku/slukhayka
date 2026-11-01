package com.slukhayka.audiobooks.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #1751 — a YouTube watch URL has no extension; declaring it `audio/mpeg`
 * made the receiver treat the resolved M4A stream as the wrong family. The
 * resolver prefers progressive M4A, so the cast playlist says `audio/mp4`
 * while plain URLs keep their extension-based answer.
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
}
