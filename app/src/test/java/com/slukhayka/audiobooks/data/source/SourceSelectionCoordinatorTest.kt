package com.slukhayka.audiobooks.data.source

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSelectionCoordinatorTest {

    @Test
    fun `selects a valid local track before probing network sources`() = runBlocking {
        val prober = RecordingProber()
        val outcome = SourceSelectionCoordinator().select(
            SourceSelectionRequest(
                editionId = "edition-a",
                operation = SourceOperation.PLAYBACK,
                candidates = listOf(
                    candidate("edition-a", "soundbooks"),
                    candidate("edition-a", "local", localAvailable = true)
                )
            ),
            prober
        )

        assertEquals("local", (outcome as SourceSelectionOutcome.Selected).candidate.source.sourceId)
        assertTrue(prober.calls.isEmpty())
    }

    @Test
    fun `shares one monotonic budget and stops on first direct success`() = runBlocking {
        val clock = FakeClock(0L)
        val prober = RecordingProber(clock, mapOf("direct-a" to false, "direct-b" to true, "legacy" to true))
        val outcome = SourceSelectionCoordinator(clock).select(
            SourceSelectionRequest(
                editionId = "edition-a",
                operation = SourceOperation.PLAYBACK,
                candidates = listOf(
                    candidate("edition-a", "direct-a", mode = SourceAccessMode.DIRECT),
                    candidate("edition-a", "direct-b", mode = SourceAccessMode.DIRECT),
                    candidate("edition-a", "legacy", mode = SourceAccessMode.UNKNOWN)
                )
            ),
            prober
        )

        assertEquals("direct-b", (outcome as SourceSelectionOutcome.Selected).candidate.source.sourceId)
        assertEquals(listOf("direct-a" to 10_000L, "direct-b" to 7_000L), prober.calls)
    }

    @Test
    fun `returns browser required only after direct and unknown candidates fail`() = runBlocking {
        val outcome = SourceSelectionCoordinator().select(
            SourceSelectionRequest(
                editionId = "edition-a",
                operation = SourceOperation.OPEN_WORK,
                candidates = listOf(
                    candidate("edition-a", "4read", mode = SourceAccessMode.BROWSER),
                    candidate("edition-a", "soundbooks", mode = SourceAccessMode.DIRECT)
                )
            ),
            RecordingProber(results = mapOf("soundbooks" to false))
        )

        assertEquals("4read", (outcome as SourceSelectionOutcome.BrowserRequired).candidate.source.sourceId)
    }

    @Test
    fun `never falls back across edition identity`() = runBlocking {
        val outcome = SourceSelectionCoordinator().select(
            SourceSelectionRequest(
                editionId = "edition-a",
                operation = SourceOperation.PLAYBACK,
                candidates = listOf(
                    candidate("edition-b", "soundbooks", mode = SourceAccessMode.DIRECT),
                    candidate("edition-b", "4read", mode = SourceAccessMode.BROWSER)
                )
            ),
            RecordingProber(results = mapOf("soundbooks" to true))
        )

        assertEquals(SourceSelectionOutcome.Unavailable, outcome)
    }

    private fun candidate(
        editionId: String,
        sourceId: String,
        localAvailable: Boolean = false,
        mode: SourceAccessMode = SourceAccessPolicy.modeFor(sourceId)
    ) = SourceSelectionCandidate(
        editionId = editionId,
        source = SourceAccessCandidate(
            sourceId = sourceId,
            sourceName = sourceId,
            url = "https://$sourceId.example/book",
            localAvailable = localAvailable,
            accessMode = mode
        )
    )

    private class FakeClock(private var now: Long) : MonotonicClock {
        override fun nowNanos(): Long = now
        fun advanceMillis(millis: Long) { now += millis * 1_000_000L }
    }

    private class RecordingProber(
        private val clock: FakeClock? = null,
        private val results: Map<String, Boolean> = emptyMap()
    ) : SourceCandidateProber {
        val calls = mutableListOf<Pair<String, Long>>()

        override suspend fun probe(candidate: SourceSelectionCandidate, budgetMillis: Long): Boolean {
            calls += candidate.source.sourceId to budgetMillis
            clock?.advanceMillis(3_000L)
            return results[candidate.source.sourceId] == true
        }
    }
}
