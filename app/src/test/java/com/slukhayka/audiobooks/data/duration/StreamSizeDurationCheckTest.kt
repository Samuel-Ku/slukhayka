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
    fun `the two shortest tracks of the observed book are caught too`() {
        // Measured: ≈5.77 MB and ≈4.52 MB → ≈360 s and ≈282 s implied.
        // At the old 10× threshold both slipped through; at 4× both are caught.
        assertTrue(StreamSizeDurationCheck.impliesImpossibleShort(52L, 5_768_253L))
        assertTrue(StreamSizeDurationCheck.impliesImpossibleShort(52L, 4_518_555L))
    }

    @Test
    fun `a short chapter with a small file is honest`() {
        // ~30 s at 128 kbps ≈ 480 KB — a legitimate short track.
        assertFalse(StreamSizeDurationCheck.impliesImpossibleShort(30L, 480_000L))
    }

    // ---------------------------------------------------------------------
    // #528 — the mirror rule, the one that faces the CDN at playback.
    // ---------------------------------------------------------------------

    @Test
    fun `the substitution is caught from the other side`() {
        // The live case: «горобець» is known to be 1701 s and the CDN hands
        // back the ~52-second interstitial (≈832 KB at 128 kbps).
        assertTrue(StreamSizeDurationCheck.impliesImpossibleSmallBody(1701L, 832_000L))
    }

    @Test
    fun `the honest body for the same chapter is accepted`() {
        // 1701 s at 128 kbps ≈ 27.2 MB, and at 64 kbps ≈ 13.6 MB — both fine.
        assertFalse(StreamSizeDurationCheck.impliesImpossibleSmallBody(1701L, 27_216_000L))
        assertFalse(StreamSizeDurationCheck.impliesImpossibleSmallBody(1701L, 13_608_000L))
    }

    @Test
    fun `a poisoned short duration never fires the body rule`() {
        // This is why the guard may not refuse playback: when the STORED
        // duration is itself the poison (52 s) and the body is the real
        // 27.2 MB chapter, the body looks fine — the mirror rule is silent.
        // The opposite rule would have refused an honest file here.
        assertFalse(StreamSizeDurationCheck.impliesImpossibleSmallBody(52L, 27_216_000L))
        assertTrue(StreamSizeDurationCheck.impliesImpossibleShort(52L, 27_216_000L))
    }

    @Test
    fun `a low-bitrate speech file is not mistaken for a substitution`() {
        // Speech sources are commonly 32–64 kbps, so the implied seconds are
        // understated against the 128 kbps assumption. A 32 kbps body for
        // 1701 s is ≈6.8 MB → ≈425 s implied, still within the 8× tolerance.
        assertFalse(StreamSizeDurationCheck.impliesImpossibleSmallBody(1701L, 6_804_000L))
    }

    @Test
    fun `unknown size or duration is never judged by the body rule`() {
        assertFalse(StreamSizeDurationCheck.impliesImpossibleSmallBody(1701L, 0L))
        assertFalse(StreamSizeDurationCheck.impliesImpossibleSmallBody(0L, 832_000L))
    }
}
