package com.slukhayka.audiobooks.data.entries

import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.ReadthroughEntity
import com.slukhayka.audiobooks.data.db.ReadthroughMapping
import com.slukhayka.audiobooks.testing.FakeAudiobookDao
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-0046 / #876 / #1286 — the write path and the read projections of reading progress. */
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

    // ------------------------------------------------------------------
    // #1286 — the projections and the honest storage outcomes
    // ------------------------------------------------------------------

    @Test
    fun `a successful record publishes the active list AND the year from one snapshot`() = runBlocking {
        val progress = ReadingProgressRecorder(dao, now = { 2_000L }, currentYear = { 2026 })
        seedPaper()

        progress.refresh()
        val before = progress.state.value as ReadingProgressRecorder.State.Ready
        assertEquals(10, before.projection.active.single().units.value)
        assertEquals(0, before.projection.year.totalFinished)
        assertFalse(before.refreshFailed)

        progress.record("paper-1", at = 2_000L, value = 40)

        val after = progress.state.value as ReadingProgressRecorder.State.Ready
        assertEquals("the active pass shows the new value", 40, after.projection.active.single().units.value)
        assertEquals(1, after.projection.active.single().journal.size)
        assertEquals("the year is the same snapshot as the list", 0, after.projection.year.totalFinished)
    }

    @Test
    fun `finishing a pass publishes it out of the active list and into the year`() = runBlocking {
        // 2026-02-02 — the year the injected calendar names.
        val at = 1_770_000_000_000L
        val progress = ReadingProgressRecorder(dao, now = { at }, currentYear = { 2026 })
        seedPaper()

        val finished = progress.finish("paper-1") as ReadingProgressRecorder.Result.Finished
        assertEquals(ReadingState.FINISHED, finished.readthrough.state)

        val state = progress.state.value as ReadingProgressRecorder.State.Ready
        assertTrue("a finished pass leaves the active list", state.projection.active.isEmpty())
        assertEquals("and lands in the year", 1, state.projection.year.totalFinished)
        assertEquals(1, state.projection.year.finishedByFormat[ReadingFormat.PAPER])
    }

    @Test
    fun `a delayed older refresh cannot overwrite the newer record`() = runBlocking {
        val controlled = ControlledDao(dao)
        val progress = ReadingProgressRecorder(controlled, now = { 2_000L }, currentYear = { 2026 })
        seedPaper()

        // The old refresh captures the PRE-record snapshot, then parks.
        val gate = CompletableDeferred<Unit>()
        controlled.readGate = gate
        val refreshJob = launch { progress.refresh() }
        while (controlled.readGate != null) yield()

        // The record lands while the old refresh is still parked.
        val recorded = progress.record("paper-1", at = 2_000L, value = 40)
        assertTrue(recorded is ReadingProgressRecorder.Result.Recorded)

        // Release the old refresh with its stale capture.
        gate.complete(Unit)
        refreshJob.join()

        val state = progress.state.value as ReadingProgressRecorder.State.Ready
        assertEquals(
            "the newer record survives the older refresh",
            40,
            state.projection.active.single().units.value
        )
        assertFalse(state.refreshFailed)
    }

    @Test
    fun `a read failure keeps the known projection and an explicit retry recovers`() = runBlocking {
        val controlled = ControlledDao(dao)
        val progress = ReadingProgressRecorder(controlled, now = { 1_000L }, currentYear = { 2026 })
        seedPaper()

        progress.refresh()
        val known = (progress.state.value as ReadingProgressRecorder.State.Ready).projection

        controlled.failReads = true
        progress.refresh()

        val stale = progress.state.value
        assertTrue("a failure is not an empty library", stale is ReadingProgressRecorder.State.Ready)
        stale as ReadingProgressRecorder.State.Ready
        assertEquals("the known list stays", known.active, stale.projection.active)
        assertEquals("the known year stays", known.year, stale.projection.year)
        assertTrue("and it is marked stale for the retry", stale.refreshFailed)

        controlled.failReads = false
        progress.refresh()

        val recovered = progress.state.value as ReadingProgressRecorder.State.Ready
        assertFalse(recovered.refreshFailed)
        assertEquals(known.active, recovered.projection.active)
    }

    @Test
    fun `a first read failure is an explicit failure, not an empty year`() = runBlocking {
        val controlled = ControlledDao(dao)
        val progress = ReadingProgressRecorder(controlled, now = { 1_000L }, currentYear = { 2026 })
        controlled.failReads = true

        progress.refresh()

        assertEquals(
            ReadingProgressRecorder.REASON_STORAGE,
            (progress.state.value as ReadingProgressRecorder.State.Failed).reason
        )
    }

    @Test
    fun `a write failure is reported as Failed and adds no journal record`() = runBlocking {
        val controlled = ControlledDao(dao)
        val progress = ReadingProgressRecorder(controlled, now = { 2_000L }, currentYear = { 2026 })
        seedPaper()

        controlled.failWrites = true
        val failed = progress.record("paper-1", at = 2_000L, value = 40)

        assertEquals(ReadingProgressRecorder.REASON_STORAGE, (failed as ReadingProgressRecorder.Result.Failed).reason)
        assertEquals("no journal record from a failed write", 0, progress.passesOfWork("work-1").single().journal.size)
    }

    @Test
    fun `cancellation is not turned into a storage failure`() = runBlocking {
        val controlled = ControlledDao(dao)
        val progress = ReadingProgressRecorder(controlled, now = { 1_000L }, currentYear = { 2026 })
        controlled.neverReads = true

        val job = launch { progress.refresh() }
        yield()
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertFalse(
            "a cancelled read is not a Failed state",
            progress.state.value is ReadingProgressRecorder.State.Failed
        )
    }

    /**
     * A controlled storage adapter over the existing [FakeAudiobookDao]: the
     * tests can make a read or a write fail, park a read, or make it hang, and
     * the module still sees the same `AudiobookDao` seam it has in production.
     */
    private class ControlledDao(private val delegate: FakeAudiobookDao) : AudiobookDao by delegate {
        var failReads = false
        var failWrites = false
        var neverReads = false

        /** The NEXT read awaits this once, then the gate clears itself. */
        var readGate: CompletableDeferred<Unit>? = null

        override suspend fun allReadthroughs(): List<ReadthroughEntity> {
            if (neverReads) awaitCancellation()
            if (failReads) throw IllegalStateException("read failed")
            val captured = delegate.allReadthroughs()
            readGate?.let { gate ->
                readGate = null
                gate.await()
            }
            return captured
        }

        override suspend fun readthroughById(id: String): ReadthroughEntity? {
            if (failReads) throw IllegalStateException("read failed")
            return delegate.readthroughById(id)
        }

        override suspend fun upsertReadthrough(entity: ReadthroughEntity) {
            if (failWrites) throw IllegalStateException("write failed")
            delegate.upsertReadthrough(entity)
        }
    }
}
