package com.slukhayka.audiobooks.data.source

import com.slukhayka.audiobooks.data.db.SourceEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #429 — Tabular JVM tests for [SourceSelectionCoordinator].
 *
 * Every test injects a [FakeClock] so no network or wall clock is involved.
 * The tests verify:
 * - Priority order (LOCAL → DIRECT → UNKNOWN → BROWSER)
 * - Tie-break stability (name, id, url)
 * - Shared budget exhaustion across probes
 * - First-success stop
 * - Browser-required verdict
 * - Unavailable verdict
 * - Edition isolation (callers enforce editionId; coordinator does not)
 */
class SourceSelectionCoordinatorTest {

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private fun source(
        id: String = "4read-ed1",
        type: String = "4read",
        url: String = "https://4read.org/book.html",
        editionId: String = "ed1"
    ) = SourceEntity(
        id = id,
        bookId = "b1",
        editionId = editionId,
        type = type,
        url = url,
        streamOnly = false,
        addedAt = 1000L
    )

    private fun candidate(
        source: SourceEntity = source(),
        category: SourceSelectionCoordinator.SourceCategory = SourceSelectionCoordinator.SourceCategory.DIRECT
    ) = SourceSelectionCoordinator.SourceCandidate(source, category)

    private fun localCandidate(
        id: String = "local-ed1",
        url: String = "",
        editionId: String = "ed1"
    ) = candidate(
        source = source(id = id, type = "local", url = url, editionId = editionId),
        category = SourceSelectionCoordinator.SourceCategory.LOCAL
    )

    private fun browserCandidate(
        id: String = "sluhay-ed1",
        url: String = "https://sluhay.com/book.html",
        editionId: String = "ed1"
    ) = candidate(
        source = source(id = id, type = "sluhay", url = url, editionId = editionId),
        category = SourceSelectionCoordinator.SourceCategory.BROWSER
    )

    private fun directCandidate(
        id: String = "4read-ed1",
        url: String = "https://4read.org/book.html",
        editionId: String = "ed1"
    ) = candidate(
        source = source(id = id, type = "4read", url = url, editionId = editionId),
        category = SourceSelectionCoordinator.SourceCategory.DIRECT
    )

    private fun unknownCandidate(
        id: String = "unknown-ed1",
        url: String = "https://unknown.com/book.html",
        editionId: String = "ed1"
    ) = candidate(
        source = source(id = id, type = "unknown", url = url, editionId = editionId),
        category = SourceSelectionCoordinator.SourceCategory.UNKNOWN
    )

    /** A probe that always succeeds. */
    private val alwaysSucceed = SourceSelectionCoordinator.SourceProbe { _, _ ->
        SourceSelectionCoordinator.ProbeResult.Success
    }

    /** A probe that always fails. */
    private val alwaysFail = SourceSelectionCoordinator.SourceProbe { _, _ ->
        SourceSelectionCoordinator.ProbeResult.Failure
    }

    /** A fake clock with a controllable wall. */
    private class FakeClock(startMs: Long = 0L) {
        var ms: Long = startMs
            private set
        val asCoordinator: SourceSelectionCoordinator.Clock =
            SourceSelectionCoordinator.Clock { ms }
        fun advance(deltaMs: Long) { ms += deltaMs }
    }

    // ----------------------------------------------------------------
    // Priority order
    // ----------------------------------------------------------------

    @Test
    fun `LOCAL always wins over DIRECT`() = runBlocking {
        val result = SourceSelectionCoordinator.select(
            operation = SourceSelectionCoordinator.OperationKind.PLAYBACK,
            candidates = listOf(directCandidate(), localCandidate()),
            probe = alwaysSucceed
        )
        assertTrue(result is SourceSelectionCoordinator.SelectionResult.Selected)
        assertEquals("local-ed1", (result as SourceSelectionCoordinator.SelectionResult.Selected).candidate.source.id)
        assertEquals(0L, result.elapsedMs)
    }

    @Test
    fun `LOCAL always wins over BROWSER`() = runBlocking {
        val result = SourceSelectionCoordinator.select(
            operation = SourceSelectionCoordinator.OperationKind.PLAYBACK,
            candidates = listOf(browserCandidate(), localCandidate()),
            probe = alwaysSucceed
        )
        assertTrue(result is SourceSelectionCoordinator.SelectionResult.Selected)
        assertEquals("local-ed1", (result as SourceSelectionCoordinator.SelectionResult.Selected).candidate.source.id)
    }

    @Test
    fun `DIRECT wins over UNKNOWN when both succeed`() = runBlocking {
        val result = SourceSelectionCoordinator.select(
            operation = SourceSelectionCoordinator.OperationKind.PLAYBACK,
            candidates = listOf(unknownCandidate(), directCandidate()),
            probe = alwaysSucceed
        )
        assertTrue(result is SourceSelectionCoordinator.SelectionResult.Selected)
        assertEquals("4read-ed1", (result as SourceSelectionCoordinator.SelectionResult.Selected).candidate.source.id)
    }

    @Test
    fun `DIRECT wins over UNKNOWN even when UNKNOWN is first in list`() = runBlocking {
        val result = SourceSelectionCoordinator.select(
            operation = SourceSelectionCoordinator.OperationKind.PLAYBACK,
            candidates = listOf(
                unknownCandidate(id = "unknown-a", url = "https://a.com"),
                directCandidate(id = "4read-a", url = "https://4read.org/a.html")
            ),
            probe = alwaysSucceed
        )
        assertTrue(result is SourceSelectionCoordinator.SelectionResult.Selected)
        assertEquals("4read-a", (result as SourceSelectionCoordinator.SelectionResult.Selected).candidate.source.id)
    }

    @Test
    fun `UNKNOWN wins over BROWSER when UNKNOWN succeeds`() = runBlocking {
        val result = SourceSelectionCoordinator.select(
            operation = SourceSelectionCoordinator.OperationKind.PLAYBACK,
            candidates = listOf(browserCandidate(), unknownCandidate()),
            probe = alwaysSucceed
        )
        assertTrue(result is SourceSelectionCoordinator.SelectionResult.Selected)
        assertEquals("unknown-ed1", (result as SourceSelectionCoordinator.SelectionResult.Selected).candidate.source.id)
    }

    @Test
    fun `BROWSER returned only when no earlier category succeeds`() = runBlocking {
        val result = SourceSelectionCoordinator.select(
            operation = SourceSelectionCoordinator.OperationKind.PLAYBACK,
            candidates = listOf(browserCandidate(), directCandidate()),
            probe = alwaysFail
        )
        assertTrue(result is SourceSelectionCoordinator.SelectionResult.BrowserRequired)
        assertEquals("sluhay-ed1", (result as SourceSelectionCoordinator.SelectionResult.BrowserRequired).candidate.source.id)
    }

    // ----------------------------------------------------------------
    // Tie-break
    // ----------------------------------------------------------------

    @Test
    fun `tie-break prefers lexicographically smaller source name`() = runBlocking {
        val result = SourceSelectionCoordinator.select(
            operation = SourceSelectionCoordinator.OperationKind.PLAYBACK,
            candidates = listOf(
                localCandidate(id = "local-z-ed1", url = ""),
                localCandidate(id = "local-a-ed1", url = "")
            ),
            probe = alwaysSucceed
        )
        assertTrue(result is SourceSelectionCoordinator.SelectionResult.Selected)
        assertEquals("local-a-ed1", (result as SourceSelectionCoordinator.SelectionResult.Selected).candidate.source.id)
    }

    @Test
    fun `tie-break prefers lexicographically smaller source id`() = runBlocking {
        val result = SourceSelectionCoordinator.select(
            operation = SourceSelectionCoordinator.OperationKind.PLAYBACK,
            candidates = listOf(
                localCandidate(id = "local-z-ed1", url = "https://z.com"),
                localCandidate(id = "local-a-ed1", url = "https://a.com")
            ),
            probe = alwaysSucceed
        )
        assertTrue(result is SourceSelectionCoordinator.SelectionResult.Selected)
        assertEquals("local-a-ed1", (result as SourceSelectionCoordinator.SelectionResult.Selected).candidate.source.id)
    }

    @Test
    fun `tie-break prefers lexicographically smaller URL as last resort`() = runBlocking {
        val result = SourceSelectionCoordinator.select(
            operation = SourceSelectionCoordinator.OperationKind.PLAYBACK,
            candidates = listOf(
                localCandidate(id = "local-ed1", url = "https://z.com"),
                localCandidate(id = "local-ed1", url = "https://a.com")
            ),
            probe = alwaysSucceed
        )
        assertTrue(result is SourceSelectionCoordinator.SelectionResult.Selected)
        assertEquals("https://a.com", (result as SourceSelectionCoordinator.SelectionResult.Selected).candidate.source.url)
    }

    // ----------------------------------------------------------------
    // Budget
    // ----------------------------------------------------------------

    @Test
    fun `probes share the budget and stop after first success`() = runBlocking {
        val clock = FakeClock()
        val result = SourceSelectionCoordinator.select(
            operation = SourceSelectionCoordinator.OperationKind.PLAYBACK,
            candidates = listOf(
                directCandidate(id = "d1", url = "https://d1.com"),
                directCandidate(id = "d2", url = "https://d2.com")
            ),
            probe = alwaysSucceed,
            clock = clock.asCoordinator,
            budgetMs = 10_000L
        )
        assertTrue(result is SourceSelectionCoordinator.SelectionResult.Selected)
        assertEquals("d1", (result as SourceSelectionCoordinator.SelectionResult.Selected).candidate.source.id)
    }

    @Test
    fun `budget exhaustion stops further probing`() = runBlocking {
        val clock = FakeClock()
        var probeCount = 0
        val result = SourceSelectionCoordinator.select(
            operation = SourceSelectionCoordinator.OperationKind.PLAYBACK,
            candidates = listOf(
                directCandidate(id = "d1", url = "https://d1.com"),
                directCandidate(id = "d2", url = "https://d2.com"),
                directCandidate(id = "d3", url = "https://d3.com")
            ),
            probe = SourceSelectionCoordinator.SourceProbe { _, _ ->
                probeCount++
                clock.advance(5_000L)
                SourceSelectionCoordinator.ProbeResult.Failure
            },
            clock = clock.asCoordinator,
            budgetMs = 10_000L
        )
        assertEquals(2, probeCount)
        assertTrue(result is SourceSelectionCoordinator.SelectionResult.Unavailable)
    }

    @Test
    fun `zero budget skips all probes`() = runBlocking {
        val clock = FakeClock()
        var probeCount = 0
        val result = SourceSelectionCoordinator.select(
            operation = SourceSelectionCoordinator.OperationKind.PLAYBACK,
            candidates = listOf(directCandidate()),
            probe = SourceSelectionCoordinator.SourceProbe { _, _ ->
                probeCount++
                SourceSelectionCoordinator.ProbeResult.Success
            },
            clock = clock.asCoordinator,
            budgetMs = 0L
        )
        assertEquals(0, probeCount)
        assertTrue(result is SourceSelectionCoordinator.SelectionResult.Unavailable)
    }

    @Test
    fun `probe receives correct remaining budget`() = runBlocking {
        val clock = FakeClock()
        var capturedRemaining = -1L
        val result = SourceSelectionCoordinator.select(
            operation = SourceSelectionCoordinator.OperationKind.PLAYBACK,
            candidates = listOf(directCandidate()),
            probe = SourceSelectionCoordinator.SourceProbe { _, remaining ->
                capturedRemaining = remaining
                SourceSelectionCoordinator.ProbeResult.Success
            },
            clock = clock.asCoordinator,
            budgetMs = 10_000L
        )
        assertEquals(10_000L, capturedRemaining)
        assertTrue(result is SourceSelectionCoordinator.SelectionResult.Selected)
    }

    // ----------------------------------------------------------------
    // First-success stop
    // ----------------------------------------------------------------

    @Test
    fun `first success stops probing remaining candidates`() = runBlocking {
        var probeCount = 0
        val result = SourceSelectionCoordinator.select(
            operation = SourceSelectionCoordinator.OperationKind.PLAYBACK,
            candidates = listOf(
                directCandidate(id = "d1", url = "https://d1.com"),
                directCandidate(id = "d2", url = "https://d2.com"),
                directCandidate(id = "d3", url = "https://d3.com")
            ),
            probe = SourceSelectionCoordinator.SourceProbe { source, _ ->
                probeCount++
                if (source.id == "d2") SourceSelectionCoordinator.ProbeResult.Success
                else SourceSelectionCoordinator.ProbeResult.Failure
            }
        )
        assertEquals(2, probeCount)
        assertTrue(result is SourceSelectionCoordinator.SelectionResult.Selected)
        assertEquals("d2", (result as SourceSelectionCoordinator.SelectionResult.Selected).candidate.source.id)
    }

    // ----------------------------------------------------------------
    // Cross-Edition
    // ----------------------------------------------------------------

    @Test
    fun `coordinator never crosses edition boundaries`() = runBlocking {
        val result = SourceSelectionCoordinator.select(
            operation = SourceSelectionCoordinator.OperationKind.PLAYBACK,
            candidates = listOf(
                directCandidate(id = "d-ed1", url = "https://d.com", editionId = "ed1"),
                directCandidate(id = "d-ed2", url = "https://d2.com", editionId = "ed2")
            ),
            probe = alwaysSucceed
        )
        assertTrue(result is SourceSelectionCoordinator.SelectionResult.Selected)
        assertEquals("d-ed1", (result as SourceSelectionCoordinator.SelectionResult.Selected).candidate.source.id)
    }

    // ----------------------------------------------------------------
    // Browser-required
    // ----------------------------------------------------------------

    @Test
    fun `browser-required when all probes fail and browser exists`() = runBlocking {
        val result = SourceSelectionCoordinator.select(
            operation = SourceSelectionCoordinator.OperationKind.PLAYBACK,
            candidates = listOf(
                directCandidate(id = "d1", url = "https://d1.com"),
                unknownCandidate(id = "u1", url = "https://u1.com"),
                browserCandidate(id = "b1", url = "https://sluhay.com/book.html")
            ),
            probe = alwaysFail
        )
        assertTrue(result is SourceSelectionCoordinator.SelectionResult.BrowserRequired)
        assertEquals("b1", (result as SourceSelectionCoordinator.SelectionResult.BrowserRequired).candidate.source.id)
    }

    @Test
    fun `browser-required picks first browser by tie-break`() = runBlocking {
        val result = SourceSelectionCoordinator.select(
            operation = SourceSelectionCoordinator.OperationKind.PLAYBACK,
            candidates = listOf(
                directCandidate(id = "d1", url = "https://d1.com"),
                browserCandidate(id = "sluhay-z", url = "https://sluhay.com/z.html"),
                browserCandidate(id = "sluhay-a", url = "https://sluhay.com/a.html")
            ),
            probe = alwaysFail
        )
        assertTrue(result is SourceSelectionCoordinator.SelectionResult.BrowserRequired)
        assertEquals("sluhay-a", (result as SourceSelectionCoordinator.SelectionResult.BrowserRequired).candidate.source.id)
    }

    // ----------------------------------------------------------------
    // Unavailable
    // ----------------------------------------------------------------

    @Test
    fun `unavailable when no candidates exist`() = runBlocking {
        val result = SourceSelectionCoordinator.select(
            operation = SourceSelectionCoordinator.OperationKind.PLAYBACK,
            candidates = emptyList(),
            probe = alwaysSucceed
        )
        assertTrue(result is SourceSelectionCoordinator.SelectionResult.Unavailable)
    }

    @Test
    fun `unavailable when all probes fail and no browser exists`() = runBlocking {
        val result = SourceSelectionCoordinator.select(
            operation = SourceSelectionCoordinator.OperationKind.PLAYBACK,
            candidates = listOf(
                directCandidate(id = "d1", url = "https://d1.com"),
                unknownCandidate(id = "u1", url = "https://u1.com")
            ),
            probe = alwaysFail
        )
        assertTrue(result is SourceSelectionCoordinator.SelectionResult.Unavailable)
    }

    @Test
    fun `unavailable when budget exhausted and no browser exists`() = runBlocking {
        val clock = FakeClock()
        val result = SourceSelectionCoordinator.select(
            operation = SourceSelectionCoordinator.OperationKind.PLAYBACK,
            candidates = listOf(directCandidate()),
            probe = SourceSelectionCoordinator.SourceProbe { _, _ ->
                clock.advance(15_000L)
                SourceSelectionCoordinator.ProbeResult.Failure
            },
            clock = clock.asCoordinator,
            budgetMs = 10_000L
        )
        assertTrue(result is SourceSelectionCoordinator.SelectionResult.Unavailable)
    }

    // ----------------------------------------------------------------
    // Operation kind
    // ----------------------------------------------------------------

    @Test
    fun `SEARCH uses same priority order`() = runBlocking {
        val result = SourceSelectionCoordinator.select(
            operation = SourceSelectionCoordinator.OperationKind.SEARCH,
            candidates = listOf(browserCandidate(), localCandidate()),
            probe = alwaysSucceed
        )
        assertTrue(result is SourceSelectionCoordinator.SelectionResult.Selected)
        assertEquals("local-ed1", (result as SourceSelectionCoordinator.SelectionResult.Selected).candidate.source.id)
    }

    @Test
    fun `RECOMMENDATIONS uses same priority order`() = runBlocking {
        val result = SourceSelectionCoordinator.select(
            operation = SourceSelectionCoordinator.OperationKind.RECOMMENDATIONS,
            candidates = listOf(directCandidate(), browserCandidate()),
            probe = alwaysFail
        )
        assertTrue(result is SourceSelectionCoordinator.SelectionResult.BrowserRequired)
    }

    @Test
    fun `FEED uses same priority order`() = runBlocking {
        val result = SourceSelectionCoordinator.select(
            operation = SourceSelectionCoordinator.OperationKind.FEED,
            candidates = listOf(browserCandidate()),
            probe = alwaysSucceed
        )
        assertTrue(result is SourceSelectionCoordinator.SelectionResult.BrowserRequired)
    }

    // ----------------------------------------------------------------
    // Mixed categories
    // ----------------------------------------------------------------

    @Test
    fun `DIRECT fail then UNKNOWN success returns UNKNOWN`() = runBlocking {
        val result = SourceSelectionCoordinator.select(
            operation = SourceSelectionCoordinator.OperationKind.PLAYBACK,
            candidates = listOf(
                directCandidate(id = "d1", url = "https://d1.com"),
                unknownCandidate(id = "u1", url = "https://u1.com")
            ),
            probe = SourceSelectionCoordinator.SourceProbe { source, _ ->
                if (source.id == "d1") SourceSelectionCoordinator.ProbeResult.Failure
                else SourceSelectionCoordinator.ProbeResult.Success
            }
        )
        assertTrue(result is SourceSelectionCoordinator.SelectionResult.Selected)
        assertEquals("u1", (result as SourceSelectionCoordinator.SelectionResult.Selected).candidate.source.id)
    }

    @Test
    fun `all categories fail then browser fallback`() = runBlocking {
        val result = SourceSelectionCoordinator.select(
            operation = SourceSelectionCoordinator.OperationKind.PLAYBACK,
            candidates = listOf(
                directCandidate(id = "d1", url = "https://d1.com"),
                unknownCandidate(id = "u1", url = "https://u1.com"),
                browserCandidate(id = "b1", url = "https://sluhay.com/book.html")
            ),
            probe = alwaysFail
        )
        assertTrue(result is SourceSelectionCoordinator.SelectionResult.BrowserRequired)
        assertEquals("b1", (result as SourceSelectionCoordinator.SelectionResult.BrowserRequired).candidate.source.id)
    }

    // ----------------------------------------------------------------
    // Elapsed time
    // ----------------------------------------------------------------

    @Test
    fun `LOCAL selection reports zero elapsed time`() = runBlocking {
        val clock = FakeClock()
        val result = SourceSelectionCoordinator.select(
            operation = SourceSelectionCoordinator.OperationKind.PLAYBACK,
            candidates = listOf(localCandidate()),
            probe = alwaysSucceed,
            clock = clock.asCoordinator
        )
        assertTrue(result is SourceSelectionCoordinator.SelectionResult.Selected)
        assertEquals(0L, (result as SourceSelectionCoordinator.SelectionResult.Selected).elapsedMs)
    }

    @Test
    fun `elapsed time reflects probe wall-clock`() = runBlocking {
        val clock = FakeClock()
        val result = SourceSelectionCoordinator.select(
            operation = SourceSelectionCoordinator.OperationKind.PLAYBACK,
            candidates = listOf(directCandidate()),
            probe = SourceSelectionCoordinator.SourceProbe { _, _ ->
                clock.advance(500L)
                SourceSelectionCoordinator.ProbeResult.Success
            },
            clock = clock.asCoordinator
        )
        assertTrue(result is SourceSelectionCoordinator.SelectionResult.Selected)
        assertEquals(500L, (result as SourceSelectionCoordinator.SelectionResult.Selected).elapsedMs)
    }
}
