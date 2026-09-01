package com.slukhayka.audiobooks.ui.catalog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaRangeValidatorTest {
    @Test
    fun `rejects an html challenge even when the request succeeded`() {
        val bytes = "<!doctype html><title>Just a moment</title>".toByteArray()

        assertFalse(MediaRangeValidator.isValid("text/html; charset=utf-8", bytes))
        assertFalse(MediaRangeValidator.isValid("application/octet-stream", bytes))
    }

    @Test
    fun `accepts audio mime magic and hls playlists`() {
        assertTrue(MediaRangeValidator.isValid("audio/mpeg", byteArrayOf(1, 2, 3)))
        assertTrue(MediaRangeValidator.isValid(null, "ID3sample".toByteArray()))
        assertTrue(MediaRangeValidator.isValid("application/vnd.apple.mpegurl", "#EXTM3U\n".toByteArray()))
    }

    @Test
    fun `rejects an unknown non-media response`() {
        assertFalse(MediaRangeValidator.isValid("text/plain", "access denied".toByteArray()))
        assertFalse(MediaRangeValidator.isValid(null, byteArrayOf()))
    }
}
