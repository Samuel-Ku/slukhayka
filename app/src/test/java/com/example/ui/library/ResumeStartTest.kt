package com.example.ui.library

import com.example.data.db.PlaybackProgressEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure JVM tests for the ADR-0008 resume start-position decision
 * (no Robolectric). One table-driven matrix pins every intent — relisten,
 * explicit chapter, resume with smart rewind, clamp-at-zero and a
 * future-dated pause marker (clock skew). The rewind tiers themselves are
 * table-tested in SmartRewind's own suite; here we pin the decision that
 * combines the requested chapter, the saved progress and the ONE ADR-0003
 * rule (no re-derived tier or boundary).
 */
class ResumeStartTest {

    private data class ResumeCase(
        val name: String,
        val requestedChapter: Int?,
        val savedChapter: Int,
        val savedPosition: Long,
        val pausedAtEpochMs: Long?,
        val nowEpochMs: Long,
        val expected: ResumeStart
    )

    /** 5 minutes in ms — a "short" pause (2 s – 10 min → 3 s rewind). */
    private val SHORT_PAUSE_MS = 5 * 60 * 1000L

    /** 30 days in ms — an "overnight" pause (≥ 24 h → 25 s rewind). */
    private val LONG_PAUSE_MS = 30 * 24 * 60 * 60 * 1000L

    @Test
    fun `resume start matrix`() {
        val cases = listOf(
            // --- relisten: chapter 0 explicitly, even with saved progress ---
            ResumeCase(
                "relisten from the first chapter ignores saved progress elsewhere",
                requestedChapter = 0, savedChapter = 3, savedPosition = 90L,
                pausedAtEpochMs = null, nowEpochMs = 1_000L,
                expected = ResumeStart(0, 0L)
            ),
            // --- first ever play: no progress, no request -------------------
            ResumeCase(
                "no progress and no request starts at chapter zero position zero",
                requestedChapter = null, savedChapter = 0, savedPosition = 0L,
                pausedAtEpochMs = null, nowEpochMs = 1_000L,
                expected = ResumeStart(0, 0L)
            ),
            // --- resume: restore the saved chapter and position -------------
            ResumeCase(
                "resume restores the saved chapter and position",
                requestedChapter = null, savedChapter = 3, savedPosition = 120L,
                pausedAtEpochMs = null, nowEpochMs = 1_000L,
                expected = ResumeStart(3, 120L)
            ),
            // --- resume with smart rewind (ADR-0003 tiers) ------------------
            ResumeCase(
                "resume with a short pause rewinds the short tier",
                requestedChapter = null, savedChapter = 2, savedPosition = 600L,
                pausedAtEpochMs = 1_000L, nowEpochMs = 1_000L + SHORT_PAUSE_MS,
                expected = ResumeStart(2, 597L) // 3 s rewind
            ),
            ResumeCase(
                "resume with an overnight pause rewinds the long tier",
                requestedChapter = null, savedChapter = 1, savedPosition = 100L,
                pausedAtEpochMs = 1_000L, nowEpochMs = 1_000L + LONG_PAUSE_MS,
                expected = ResumeStart(1, 75L) // 25 s rewind
            ),
            ResumeCase(
                "rewind clamps at zero when the position is smaller than the rewind",
                requestedChapter = null, savedChapter = 0, savedPosition = 2L,
                pausedAtEpochMs = 1_000L, nowEpochMs = 1_000L + SHORT_PAUSE_MS,
                expected = ResumeStart(0, 0L) // 2 s position vs 3 s rewind
            ),
            // --- resume without a pause marker rewinds nothing --------------
            ResumeCase(
                "no pause marker rewinds nothing",
                requestedChapter = null, savedChapter = 4, savedPosition = 42L,
                pausedAtEpochMs = null, nowEpochMs = 1_000L,
                expected = ResumeStart(4, 42L)
            ),
            // --- future-dated pause marker (clock skew) rewinds nothing -----
            ResumeCase(
                "future-dated pause marker rewinds nothing",
                requestedChapter = null, savedChapter = 1, savedPosition = 50L,
                pausedAtEpochMs = 2_000L, nowEpochMs = 1_000L,
                expected = ResumeStart(1, 50L) // negative pause → no rewind
            ),
            // --- explicit chapter request -----------------------------------
            ResumeCase(
                "explicit chapter in the saved chapter keeps the saved position",
                requestedChapter = 2, savedChapter = 2, savedPosition = 90L,
                pausedAtEpochMs = null, nowEpochMs = 1_000L,
                expected = ResumeStart(2, 90L)
            ),
            ResumeCase(
                "explicit chapter elsewhere starts that chapter at zero",
                requestedChapter = 5, savedChapter = 2, savedPosition = 90L,
                pausedAtEpochMs = null, nowEpochMs = 1_000L,
                expected = ResumeStart(5, 0L)
            ),
            ResumeCase(
                "explicit chapter with no progress starts that chapter at zero",
                requestedChapter = 7, savedChapter = 0, savedPosition = 0L,
                pausedAtEpochMs = null, nowEpochMs = 1_000L,
                expected = ResumeStart(7, 0L)
            ),
            ResumeCase(
                "explicit chapter still rewinds when a pause marker exists",
                requestedChapter = 2, savedChapter = 2, savedPosition = 600L,
                pausedAtEpochMs = 1_000L, nowEpochMs = 1_000L + SHORT_PAUSE_MS,
                expected = ResumeStart(2, 597L) // 3 s rewind
            )
        )

        cases.forEach { c ->
            val progress = PlaybackProgressEntity(
                editionId = "edition-1",
                bookId = "book-1",
                currentChapterIndex = c.savedChapter,
                currentPositionSeconds = c.savedPosition,
                lastPausedAtEpochMs = c.pausedAtEpochMs
            )
            val actual = computeResumeStart(
                requestedChapter = c.requestedChapter,
                progress = progress,
                nowEpochMs = c.nowEpochMs
            )
            assertEquals(c.name, c.expected, actual)
        }
    }
}
