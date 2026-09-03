package com.slukhayka.audiobooks.ui

import com.slukhayka.audiobooks.ui.screens.looksLikeAudio
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #476 — the session-side audio hint must fire for 4read playlist manifests,
 * not only for direct tracks. The live book page serves its chapter topology
 * as `/m3u/<id>.txt` (or `.m3u`); with only `.m3u8`/`.pl.txt` recognised,
 * those manifest fetches passed unseen, `capturedAudioUrls` stayed empty and
 * «Додати до медіатеки» honestly reported «Аудіо ще не знайдено».
 */
class WebSourceBrowserAudioCaptureTest {

    @Test
    fun `recognises the live 4read txt manifest`() {
        assertTrue(
            looksLikeAudio("https://4read.org/m3u/7589.txt")
        )
    }

    @Test
    fun `recognises the live 4read txt manifest with query parameters`() {
        assertTrue(
            looksLikeAudio("https://4read.org/m3u/7589.txt?token=abc123")
        )
    }

    @Test
    fun `recognises the live 4read m3u manifest`() {
        assertTrue(
            looksLikeAudio("https://4read.org/m3u/7589.m3u")
        )
    }

    @Test
    fun `keeps recognising direct tracks`() {
        assertTrue(looksLikeAudio("https://s1.reasd.org/uploads/audio/7589/01.mp3"))
        assertTrue(looksLikeAudio("https://s1.reasd.org/track.m4b"))
        assertTrue(looksLikeAudio("https://s1.reasd.org/stream.m3u8"))
        assertTrue(looksLikeAudio("https://s1.reasd.org/chapter.opus"))
    }

    @Test
    fun `ignores non-audio page resources`() {
        assertFalse(looksLikeAudio("https://4read.org/7589-neostannij-bij.html"))
        assertFalse(looksLikeAudio("https://4read.org/templates/style.css"))
        assertFalse(looksLikeAudio("https://4read.org/engine/classes/js/app.js"))
        assertFalse(looksLikeAudio("https://4read.org/uploads/posts/2026-06/medium/neostannij-bij.webp"))
    }
}
