package com.slukhayka.audiobooks.ui.catalog

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogAvailabilityPolicyTest {

    @Test
    fun `local verdict TTLs are exact at the expiry boundary`() {
        val observed = 1_000L
        assertTrue(CatalogAvailabilityPolicy.isFresh(true, observed, observed + 6 * 60 * 60 * 1_000L - 1))
        assertFalse(CatalogAvailabilityPolicy.isFresh(true, observed, observed + 6 * 60 * 60 * 1_000L))
        assertTrue(CatalogAvailabilityPolicy.isFresh(false, observed, observed + 15 * 60 * 1_000L - 1))
        assertFalse(CatalogAvailabilityPolicy.isFresh(false, observed, observed + 15 * 60 * 1_000L))
        assertTrue(CatalogAvailabilityPolicy.isVerifiedProfileFresh(observed, observed + 24 * 60 * 60 * 1_000L - 1))
        assertFalse(CatalogAvailabilityPolicy.isVerifiedProfileFresh(observed, observed + 24 * 60 * 60 * 1_000L))
    }

    @Test
    fun `first playing Source wins cancels sibling and never crosses Edition`() = runTest {
        val firstGate = CompletableDeferred<Unit>()
        val secondGate = CompletableDeferred<Unit>()
        val attempts = mutableListOf<String>()
        var cancelledSibling = false
        val candidates = listOf(
            EditionSourceCandidate("source-a", "edition-1"),
            EditionSourceCandidate("source-b", "edition-1"),
            EditionSourceCandidate("other-narration", "edition-2")
        )

        val result = BoundedEditionPlaybackRace(this).race(
            selectedEditionId = "edition-1",
            candidates = candidates
        ) { source ->
            attempts += source.sourceId
            when (source.sourceId) {
                "source-a" -> {
                    try {
                        firstGate.await()
                        SourceAttemptVerdict.TEMPORARY_FAILURE
                    } finally {
                        cancelledSibling = true
                    }
                }
                "source-b" -> {
                    secondGate.await()
                    SourceAttemptVerdict.PLAYING
                }
                else -> error("cross-Edition fallback")
            }
        }
        // The race is suspended; both and only same-Edition attempts started.
        runCurrent()
        assertEquals(listOf("source-a", "source-b"), attempts)
        secondGate.complete(Unit)
        advanceUntilIdle()

        assertEquals("source-b", result.await().source?.sourceId)
        assertTrue(cancelledSibling)
        assertFalse(attempts.contains("other-narration"))
    }

    @Test
    fun `late cancellation-ignoring attempt cannot replace first success`() = runTest {
        val lateGate = CompletableDeferred<Unit>()
        val fastGate = CompletableDeferred<Unit>()
        val result = BoundedEditionPlaybackRace(this).race(
            selectedEditionId = "edition-1",
            candidates = listOf(
                EditionSourceCandidate("late", "edition-1"),
                EditionSourceCandidate("fast", "edition-1")
            )
        ) { source ->
            if (source.sourceId == "late") {
                withContext(NonCancellable) {
                    lateGate.await()
                    SourceAttemptVerdict.PLAYING
                }
            } else {
                fastGate.await()
                SourceAttemptVerdict.PLAYING
            }
        }
        runCurrent()
        fastGate.complete(Unit)
        runCurrent()
        lateGate.complete(Unit)
        advanceUntilIdle()

        assertEquals("fast", result.await().source?.sourceId)
    }
}
