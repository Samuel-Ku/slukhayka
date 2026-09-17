package com.slukhayka.audiobooks.data.entries

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-0046 / #876 — units stay their own, and finishing one format changes none. */
class ReadingProgressPolicyTest {

    private fun pass(
        id: String,
        format: ReadingFormat,
        state: ReadingState,
        value: Int,
        startedAt: Long,
        finishedAt: Long? = null,
        journal: List<ReadthroughProgressEntry> = emptyList()
    ) = Readthrough(
        id = id,
        libraryEntryId = "entry-1",
        workId = "work-1",
        format = format,
        state = state,
        startedAt = startedAt,
        finishedAt = finishedAt,
        editionId = if (format == ReadingFormat.AUDIO) "edition-1" else null,
        units = ReadingUnits(ReadthroughPolicy.unitFor(format), value),
        journal = journal
    )

    private val paper = pass("paper-1", ReadingFormat.PAPER, ReadingState.IN_PROGRESS, 120, 1_000L)
    private val audio = pass("audio-1", ReadingFormat.AUDIO, ReadingState.IN_PROGRESS, 3_600, 1_000L)

    @Test
    fun `formats are reported side by side, each in its OWN unit`() {
        val progress = ReadingProgressPolicy.byFormat(listOf(paper, audio))

        assertEquals(2, progress.size)
        val paperRow = progress.first { it.format == ReadingFormat.PAPER }
        val audioRow = progress.first { it.format == ReadingFormat.AUDIO }
        assertEquals(ReadingUnit.PAGES, paperRow.unit)
        assertEquals(120, paperRow.value)
        assertEquals(ReadingUnit.SECONDS, audioRow.unit)
        assertEquals(3_600, audioRow.value)
    }

    @Test
    fun `finishing the paper pass leaves the audio pass exactly as it was`() {
        val finishedPaper = ReadthroughPolicy.finish(paper, at = 500_000L)!!
        val progress = ReadingProgressPolicy.byFormat(listOf(finishedPaper, audio))

        val audioRow = progress.first { it.format == ReadingFormat.AUDIO }
        assertEquals(
            "completion belongs to the READTHROUGH, not the Work",
            ReadingState.IN_PROGRESS,
            audioRow.state
        )
        assertEquals(3_600, audioRow.value)
        assertEquals("the audio progress did not move", audio, audioRow.passes.single())
    }

    @Test
    fun `a re-read is a new pass and the history keeps both`() {
        val first = ReadthroughPolicy.finish(paper, at = 500_000L)!!
        val second = ReadthroughPolicy.restart(first, newId = "paper-2", startedAt = 900_000L)!!

        val row = ReadingProgressPolicy.byFormat(listOf(first, second)).single()

        assertEquals("the ACTIVE pass fronts the format", "paper-2", row.passes.first().id)
        assertEquals("one finished pass so far", 1, row.finishedCount)
        assertEquals("both passes remain in the journal", 2, row.passes.size)
        assertTrue(row.passes.any { it.id == "paper-1" && it.state == ReadingState.FINISHED })
    }

    @Test
    fun `the yearly goal counts FINISHED passes of that year only`() {
        val finishedThisYear = pass(
            "paper-done",
            ReadingFormat.PAPER,
            ReadingState.FINISHED,
            200,
            1_000L,
            finishedAt = 1_770_000_000_000L // 2026-02-02
        )
        val finishedLastYear = pass(
            "paper-old",
            ReadingFormat.PAPER,
            ReadingState.FINISHED,
            180,
            1_000L,
            finishedAt = 1_735_689_600_000L // 2025-01-01
        )
        val unfinished = pass("paper-now", ReadingFormat.PAPER, ReadingState.IN_PROGRESS, 10, 1_000L)

        val goal = ReadingProgressPolicy.yearlyGoal(
            listOf(finishedThisYear, finishedLastYear, unfinished),
            year = 2026
        )

        assertEquals(2026, goal.year)
        assertEquals(1, goal.totalFinished)
        assertEquals(1, goal.finishedByFormat[ReadingFormat.PAPER])
        assertEquals(null, goal.finishedByFormat[ReadingFormat.AUDIO])
    }

    @Test
    fun `the goal counts passes, never sums pages and minutes`() {
        val finishedPaper = pass(
            "paper", ReadingFormat.PAPER, ReadingState.FINISHED, 320, 1_000L,
            finishedAt = 1_770_000_000_000L
        )
        val finishedAudio = pass(
            "audio", ReadingFormat.AUDIO, ReadingState.FINISHED, 28_800, 1_000L,
            finishedAt = 1_770_000_100_000L
        )

        val goal = ReadingProgressPolicy.yearlyGoal(listOf(finishedPaper, finishedAudio), year = 2026)

        assertEquals("two finished passes", 2, goal.totalFinished)
        assertEquals(1, goal.finishedByFormat[ReadingFormat.PAPER])
        assertEquals(1, goal.finishedByFormat[ReadingFormat.AUDIO])
    }

    @Test
    fun `the journal keeps every record in the unit it was observed in`() {
        val withJournal = paper.copy(
            journal = listOf(
                ReadthroughProgressEntry(at = 2_000L, units = ReadingUnits(ReadingUnit.PAGES, 40)),
                ReadthroughProgressEntry(at = 3_000L, units = ReadingUnits(ReadingUnit.PAGES, 75))
            )
        )

        val journal = ReadingProgressPolicy.journal(listOf(withJournal, audio))

        assertEquals(2, journal.size)
        assertEquals(3_000L, journal.first().second.at)
        assertTrue(
            "pages stay pages in the journal",
            journal.all { (_, entry) -> entry.units.unit == ReadingUnit.PAGES }
        )
    }
}
