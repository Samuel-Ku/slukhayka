package com.slukhayka.audiobooks.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * #528 — the stream-body guard's force, proven without a player.
 *
 * The substitution is intermittent, so the guard's whole value is that it
 * knocks once more before giving up — and that it never leaves a rejected
 * body open.
 */
class StreamBodyGuardTest {

    /** The observed refusal: a 52-second body for a chapter known to be 1701 s. */
    private val substituted = 1_701L to 52L

    @Test
    fun `one more knock returns the real file`() {
        val lengths = ArrayDeque(listOf(832_000L, 27_216_000L))
        val verdicts = ArrayDeque(listOf<Pair<Long, Long>?>(substituted, null))
        var opens = 0
        var closes = 0

        val accepted = StreamBodyGuard.accept(
            uri = "https://example.org/ch1.mp3",
            open = { opens++; lengths.removeFirst() },
            close = { closes++ },
            expectation = { verdicts.removeFirst() }
        )

        assertEquals(27_216_000L, accepted)
        assertEquals(2, opens)
        // The rejected ad body was dropped before the second knock.
        assertEquals(1, closes)
    }

    @Test
    fun `a persistent substitution is refused, not played`() {
        var opens = 0
        var closes = 0
        try {
            StreamBodyGuard.accept(
                uri = "https://example.org/ch1.mp3",
                open = { opens++; 832_000L },
                close = { closes++ },
                expectation = { substituted }
            )
            fail("an ad body must not be accepted")
        } catch (refused: SubstitutedStreamException) {
            assertEquals(1_701L, refused.expectedSeconds)
            assertEquals(52L, refused.observedSeconds)
        }
        assertEquals(StreamBodyGuard.MAX_OPEN_ATTEMPTS, opens)
        // Every rejected body was dropped, including the last one.
        assertEquals(StreamBodyGuard.MAX_OPEN_ATTEMPTS, closes)
    }

    @Test
    fun `an honest body is accepted on the first knock and left open`() {
        var opens = 0
        var closes = 0

        val accepted = StreamBodyGuard.accept(
            uri = "https://example.org/ch1.mp3",
            open = { opens++; 27_216_000L },
            close = { closes++ },
            expectation = { null }
        )

        assertEquals(27_216_000L, accepted)
        assertEquals(1, opens)
        assertEquals(0, closes)
    }

    @Test
    fun `the refusal names the numbers it saw`() {
        val refused = try {
            StreamBodyGuard.accept(
                uri = "https://example.org/ch1.mp3",
                open = { 832_000L },
                close = { },
                expectation = { substituted }
            )
            null
        } catch (thrown: SubstitutedStreamException) {
            thrown
        }

        assertNotNull(refused)
        assertTrue(refused!!.message!!.contains("1701"))
        assertEquals("https://example.org/ch1.mp3", refused.streamUri)
    }
}
