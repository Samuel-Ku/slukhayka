package com.slukhayka.audiobooks.data.achievements

import com.slukhayka.audiobooks.data.listening.ListeningObservation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AchievementRecorderTest {
    @Test fun `slow writes preserve immutable intervals and queued facts stay ordered without delaying accepted feedback`() = runTest {
        val store = MemoryAchievementStore()
        val gate = CompletableDeferred<Unit>()
        val written = mutableListOf<Long>()
        val recorder = AchievementRecorder(backgroundScope, store, { observation -> gate.await(); written += (observation as ListeningObservation.Played).millis })
        recorder.recordObservation(ListeningObservation.Played(650L))
        recorder.recordObservation(ListeningObservation.Played(150L))
        recorder.recordObservation(ListeningObservation.Played(400L))
        val accepted = launch { recorder.recordFact(AchievementFact.REVIEW_ACCEPTED) }
        runCurrent()
        assertTrue(accepted.isCompleted)
        assertEquals(emptySet<AchievementFact>(),store.observeFacts().first())
        gate.complete(Unit); runCurrent()
        assertEquals(listOf(650L,150L,400L), written)
        assertTrue(accepted.isCompleted)
        assertEquals(setOf(AchievementFact.REVIEW_ACCEPTED),store.observeFacts().first())
    }
    @Test fun `a failed write retries the same interval and never drops the following one`() = runTest {
        val store = MemoryAchievementStore()
        var fail = true
        val written = mutableListOf<Long>()
        var failures = 0
        val recorder = AchievementRecorder(backgroundScope, store, { observation ->
            if (fail) { fail=false; error("temporary database lock") }
            written += (observation as ListeningObservation.Played).millis
        }, { failures++ })
        recorder.recordObservation(ListeningObservation.Played(100L))
        recorder.recordObservation(ListeningObservation.Played(200L))
        recorder.captureFact(AchievementFact.PLAYBACK_STARTED)
        runCurrent(); advanceTimeBy(1001L); runCurrent()
        assertEquals(1,failures)
        assertEquals(listOf(100L,200L),written)
        assertEquals(setOf(AchievementFact.PLAYBACK_STARTED),store.observeFacts().first())
    }

    /**
     * #1173 (T9) — the counters ride the same queue, so an arm can never be
     * lost to a database that is momentarily retrying, and the stop that
     * follows a session cannot overtake it.
     */
    @Test fun `counters and observations keep one order even while a write retries`() = runTest {
        val store = MemoryAchievementStore()
        val order = mutableListOf<String>()
        var fail = true
        val recorder = AchievementRecorder(backgroundScope, store, { observation -> 
            if (fail) { fail = false; error("temporary database lock") }
            order += when (observation) {
                is ListeningObservation.Played -> "played:${observation.millis}"
                ListeningObservation.Stopped -> "stopped"
            }
        }, {})
        recorder.recordObservation(ListeningObservation.Played(1000L))
        recorder.recordObservation(ListeningObservation.Stopped)
        recorder.captureCounter(AchievementCounter.END_OF_CHAPTER_ARM)
        runCurrent(); advanceTimeBy(1001L); runCurrent()
        assertEquals(listOf("played:1000", "stopped"), order)
        assertEquals(mapOf(AchievementCounter.END_OF_CHAPTER_ARM to 1L), store.counters)
    }
}
