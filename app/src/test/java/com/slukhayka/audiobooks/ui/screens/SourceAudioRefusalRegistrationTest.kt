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
 */
class SourceAudioRefusalRegistrationTest {

    @Test
    fun `every catalogued audio source is refusable - the spec-47 wave joins`() {
        assertEquals(
            listOf(
                "4read", "sluhayua", "soundbooks", "audiobookmp3", "lihtar",
                "audiobookcoua", "chytaylo", "ukrainianaudiobooks"
            ),
            REFUSABLE_SOURCES
        )
    }
}