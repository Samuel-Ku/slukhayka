package com.slukhayka.audiobooks.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Spec-47 T5 — registration pins. ADR-0037's invariant is that every
 * catalogued AUDIO source is refusable from «Аудіо джерел»: the screen's
 * list is the one place a newly registered source must join. A registration
 * that forgets it would leave the source un-refusable (its audio could come
 * back through automatic paths against the listener's will), so the list
 * content is pinned here — a new source extends the expectation in the same
 * commit that registers it.
 *
 * The scam source (4read) is deliberately NOT here: it is always refused
 * built-in, so a listener checkbox for it would be a lie.
 */
class SourceAudioRefusalRegistrationTest {

    @Test
    fun `every catalogued audio source choice is refusable - waves 47 and 50 joined`() {
        assertEquals(
            listOf(
                "sluhayua", "soundbooks", "audiobookmp3", "lihtar",
                "audiobookcoua", "chytaylo", "ukrainianaudiobooks",
                "knigionline", "chitaka"
            ),
            REFUSABLE_SOURCES
        )
    }

    @Test
    fun `the scam source is never offered as a listener choice`() {
        assertEquals(false, REFUSABLE_SOURCES.contains("4read"))
    }
}
