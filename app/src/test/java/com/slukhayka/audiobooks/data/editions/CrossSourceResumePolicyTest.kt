package com.slukhayka.audiobooks.data.editions

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #531 — the targeted reliability rules: ready files are preserved, a resume
 * asks only for the missing chapters, a mismatch writes nothing, and the
 * decision never depends on which Source produced a ready file.
 */
class CrossSourceResumePolicyTest {

    private val required = (0 until 8).toList()

    @Test
    fun `ready files are preserved and only the missing chapters are requested`() {
        // Chapters 0-4 arrived from the ORIGINAL source before it failed.
        val plan = CrossSourceResumePolicy.plan(
            readyChapterIndexes = setOf(0, 1, 2, 3, 4),
            requiredChapterIndexes = required,
            sameEdition = true,
            chapterMappingSafe = true
        )

        assertEquals(ResumeDecision.RESUME_MISSING_ONLY, plan.decision)
        assertEquals(listOf(5, 6, 7), plan.missingChapterIndexes)
    }

    @Test
    fun `a fully downloaded edition asks for nothing`() {
        val plan = CrossSourceResumePolicy.plan(
            readyChapterIndexes = required.toSet(),
            requiredChapterIndexes = required,
            sameEdition = true,
            chapterMappingSafe = true
        )

        assertEquals(ResumeDecision.NOTHING_MISSING, plan.decision)
        assertEquals(emptyList<Int>(), plan.missingChapterIndexes)
    }

    @Test
    fun `a mapping mismatch or another edition writes no file at all`() {
        val mismatch = CrossSourceResumePolicy.plan(
            readyChapterIndexes = setOf(0, 1),
            requiredChapterIndexes = required,
            sameEdition = true,
            chapterMappingSafe = false
        )
        assertEquals(ResumeDecision.PAUSE_INCOMPATIBLE, mismatch.decision)
        assertEquals(emptyList<Int>(), mismatch.missingChapterIndexes)

        val otherEdition = CrossSourceResumePolicy.plan(
            readyChapterIndexes = setOf(0, 1),
            requiredChapterIndexes = required,
            sameEdition = false,
            chapterMappingSafe = true
        )
        assertEquals(ResumeDecision.PAUSE_INCOMPATIBLE, otherEdition.decision)
        assertEquals(emptyList<Int>(), otherEdition.missingChapterIndexes)
    }

    @Test
    fun `the plan ignores duplicate requirements and never repeats a chapter`() {
        val plan = CrossSourceResumePolicy.plan(
            readyChapterIndexes = setOf(1),
            // A malformed chapter list must not turn into duplicate requests.
            requiredChapterIndexes = listOf(0, 1, 2, 2, 0, 3),
            sameEdition = true,
            chapterMappingSafe = true
        )

        assertEquals(ResumeDecision.RESUME_MISSING_ONLY, plan.decision)
        assertEquals(listOf(0, 2, 3), plan.missingChapterIndexes)
    }
}
