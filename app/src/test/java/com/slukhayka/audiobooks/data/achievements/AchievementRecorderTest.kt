package com.slukhayka.audiobooks.data.achievements

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
        val recorder = AchievementRecorder(backgroundScope, store, { millis -> gate.await(); written += millis })
        recorder.recordDuration(650L); recorder.recordDuration(150L); recorder.recordDuration(400L)
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
        val recorder = AchievementRecorder(backgroundScope, store, { millis ->
            if (fail) { fail=false; error("temporary database lock") }
            written += millis
        }, { failures++ })
        recorder.recordDuration(100L); recorder.recordDuration(200L)
        recorder.captureFact(AchievementFact.PLAYBACK_STARTED)
        runCurrent(); advanceTimeBy(1001L); runCurrent()
        assertEquals(1,failures)
        assertEquals(listOf(100L,200L),written)
        assertEquals(setOf(AchievementFact.PLAYBACK_STARTED),store.observeFacts().first())
    }
}
