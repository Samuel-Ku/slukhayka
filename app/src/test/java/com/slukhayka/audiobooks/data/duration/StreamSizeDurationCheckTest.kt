package com.slukhayka.audiobooks.data.duration

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #528 — a 52-second claim cannot describe a 51 MB file. */
class StreamSizeDurationCheckTest {

    @Test
    fun `the observed single-chapter case is caught`() {
        // «Планета туману»: 52 s stored, track ≈ 51 MB → implied ≈ 53 min.
        assertTrue(
            StreamSizeDurationCheck.impliesImpossibleShort(
                durationSeconds = 52L,
                contentLength = 51_021_112L
            )
        )
    }

    @Test
    fun `a real duration for the same size is accepted`() {
        // «горобець»: 1701 s (28 min) against a comparable file — plausible.
        assertFalse(
            StreamSizeDurationCheck.impliesImpossibleShort(
                durationSeconds = 1701L,
                contentLength = 51_021_112L
            )
        )
    }

    @Test
    fun `unknown size or duration is never judged`() {
        assertFalse(StreamSizeDurationCheck.impliesImpossibleShort(52L, 0L))
        assertFalse(StreamSizeDurationCheck.impliesImpossibleShort(0L, 51_021_112L))
    }

    @Test
    fun `a short chapter with a small file is honest`() {
        // ~30 s at 128 kbps ≈ 480 KB — a legitimate short track.
        assertFalse(StreamSizeDurationCheck.impliesImpossibleShort(30L, 480_000L))
    }
}
