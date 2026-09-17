package com.slukhayka.audiobooks.data.entries

import com.slukhayka.audiobooks.testing.FakeAudiobookDao
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-0046 / #876 — the write path of reading progress. */
class ReadingProgressRecorderTest {

    private val dao = FakeAudiobookDao()
    private val recorder = ReadingProgressRecorder(dao)

    private suspend fun seedPaper(): Readthrough {
        val pass = ReadthroughPolicy.start(
            id = "paper-1",
            libraryEntryId = "entry-1",
            workId = "work-1",
            format = ReadingFormat.PAPER,
            startedAt = 1_000L,
            value = 10
        )!!
        with(com.slukhayka.audiobooks.data.db.ReadthroughMapping) {
            dao.upsertReadthrough(pass.toEntity())
        }
        return pass
    }

    @Test
    fun `progress is appended to the journal in the pass's own unit`() = runBlocking {
        seedPaper()

        val result = recorder.record("paper-1", at = 2_000L, value = 40) as ReadingProgressRecorder.Result.Recorded
        val stored = recorder.passesOfWork("work-1").single()

        assertEquals(40, stored.units.value)
        assertEquals(ReadingUnit.PAGES, stored.units.unit)
        assertEquals(1, stored.journal.size)
        assertEquals(40, result.readthrough.units.value)
    }

    @Test
    fun `finishing one pass leaves the others untouched`() = runBlocking {
        seedPaper()
        val audio = ReadthroughPolicy.start(
            id = "audio-1", libraryEntryId = "entry-1", workId = "work-1",
            format = ReadingFormat.AUDIO, startedAt = 1_000L, editionId = "edition-1", value = 300
        )!!
        with(com.slukhayka.audiobooks.data.db.ReadthroughMapping) { dao.upsertReadthrough(audio.toEntity()) }

        val finished = recorder.finish("paper-1", at = 5_000L) as ReadingProgressRecorder.Result.Finished

        assertEquals(ReadingState.FINISHED, finished.readthrough.state)
        assertEquals("the audio pass is not finished by the paper one", ReadingState.IN_PROGRESS, recorder.passesOfWork("work-1").first { it.id == "audio-1" }.state)
    }

    @Test
    fun `a finished pass refuses further progress instead of rewriting history`() = runBlocking {
        seedPaper()
        recorder.finish("paper-1", at = 5_000L)

        val refused = recorder.record("paper-1", at = 6_000L, value = 99)

        assertEquals(
            ReadingProgressRecorder.REASON_NOT_OPEN,
            (refused as ReadingProgressRecorder.Result.Refused).reason
        )
        assertEquals("no new journal record", 0, recorder.passesOfWork("work-1").single().journal.size)
    }

    @Test
    fun `a re-read adds a NEW pass and keeps the old one in history`() = runBlocking {
        seedPaper()
        recorder.finish("paper-1", at = 5_000L)

        recorder.restart("paper-1", newId = "paper-2", startedAt = 9_000L)

        val passes = recorder.passesOfWork("work-1")
        assertEquals(2, passes.size)
        assertEquals(
            "the finished pass stays exactly as it was",
            ReadingState.FINISHED,
            passes.first { it.id == "paper-1" }.state
        )
        val second = passes.first { it.id == "paper-2" }
        assertEquals(ReadingState.IN_PROGRESS, second.state)
        assertEquals(0, second.units.value)
        assertTrue(second.journal.isEmpty())
    }

    @Test
    fun `an unknown pass is refused, never silently accepted`() = runBlocking {
        val refused = recorder.record("nope", at = 1_000L, value = 1)

        assertEquals(
            ReadingProgressRecorder.REASON_UNKNOWN_PASS,
            (refused as ReadingProgressRecorder.Result.Refused).reason
        )
    }
}
