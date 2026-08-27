package com.slukhayka.audiobooks.data.source

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the YouTube stream resolution seam (spec 2026-08-26):
 * the watch URL is the persisted locator, the signed stream URL is resolved
 * per use, and the selection prefers progressive M4A.
 */
class YouTubeStreamResolverTest {

    @Test
    fun `selection prefers the highest bitrate progressive m4a`() {
        val best = YouTubeStreamResolver.pickBestAudio(
            listOf(
                AudioStreamSpec("https://cdn/opus-high", isM4a = false, isDirectUrl = true, bitrateKbps = 160),
                AudioStreamSpec("https://cdn/manifest.m3u8", isM4a = true, isDirectUrl = false, bitrateKbps = 999),
                AudioStreamSpec("https://cdn/m4a-128", isM4a = true, isDirectUrl = true, bitrateKbps = 128),
                AudioStreamSpec("https://cdn/m4a-48", isM4a = true, isDirectUrl = true, bitrateKbps = 48)
            )
        )

        assertEquals("https://cdn/m4a-128", best?.url)
    }

    @Test
    fun `a direct opus stream wins over nothing - a manifest never does`() {
        val onlyManifest = YouTubeStreamResolver.pickBestAudio(
            listOf(AudioStreamSpec("https://cdn/manifest.mpd", isM4a = true, isDirectUrl = false, bitrateKbps = 256))
        )
        assertNull("manifests are never this concept", onlyManifest)

        val fallback = YouTubeStreamResolver.pickBestAudio(
            listOf(AudioStreamSpec("https://cdn/opus-96", isM4a = false, isDirectUrl = true, bitrateKbps = 96))
        )
        assertEquals("https://cdn/opus-96", fallback?.url)
    }

    @Test
    fun `plain urls pass through unchanged and youtube urls resolve through the extractor`() = runBlocking {
        val resolver = YouTubeStreamResolver(
            extractAudioStreams = { watchUrl ->
                assertEquals("https://www.youtube.com/watch?v=ozaZXk5Qcwc", watchUrl)
                listOf(AudioStreamSpec("https://cdn/fresh.m4a", isM4a = true, isDirectUrl = true, bitrateKbps = 128))
            }
        )

        assertEquals("https://4read.org/audio.mp3", resolver.resolve("https://4read.org/audio.mp3"))
        assertEquals("https://cdn/fresh.m4a", resolver.resolve("https://www.youtube.com/watch?v=ozaZXk5Qcwc"))
    }

    @Test
    fun `an extraction failure resolves to null - the honest failure`() = runBlocking {
        val resolver = YouTubeStreamResolver(
            extractAudioStreams = { throw java.io.IOException("extraction broke") }
        )

        assertNull(resolver.resolve("https://www.youtube.com/watch?v=ozaZXk5Qcwc"))
        assertTrue(YouTubeTracks.isYouTubeWatchUrl("https://www.youtube.com/watch?v=ozaZXk5Qcwc"))
    }
}
