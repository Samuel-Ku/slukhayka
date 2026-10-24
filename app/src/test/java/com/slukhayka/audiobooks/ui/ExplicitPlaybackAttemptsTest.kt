package com.slukhayka.audiobooks.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1240 — the shared start seam: one place resets the failure and sets recovery. */
class ExplicitPlaybackAttemptsTest {

    private val gate = AutomaticPlaybackRecoveryGate()
    private var cleared = 0
    private val attempts = ExplicitPlaybackAttempts(gate) { cleared++ }

    @Test
    fun `every kind clears the previous failure exactly once`() {
        attempts.begin("book", ExplicitPlaybackAttempts.Kind.RESUME)
        attempts.begin("book", ExplicitPlaybackAttempts.Kind.RELISTEN)
        attempts.begin("book", ExplicitPlaybackAttempts.Kind.BOOKMARK)
        assertEquals(3, cleared)
    }

    @Test
    fun `resume supersedes the previous recovery allowance`() {
        gate.arm("book")
        attempts.begin("book", ExplicitPlaybackAttempts.Kind.RESUME)
        assertFalse("resume must not inherit the old allowance", gate.claimFailure("book"))
    }

    @Test
    fun `relisten supersedes the old allowance and arms only on success`() {
        gate.arm("book")
        val attempt = attempts.begin("book", ExplicitPlaybackAttempts.Kind.RELISTEN)
        assertFalse("preparation must not spend the new budget", gate.claimFailure("book"))

        assertTrue(attempts.armOnSuccess(attempt, "book"))
        assertTrue(gate.claimFailure("book"))
    }

    @Test
    fun `bookmark arms only when its anchor is installed`() {
        val attempt = attempts.begin("book", ExplicitPlaybackAttempts.Kind.BOOKMARK)
        assertFalse("preparation must not spend the new budget", gate.claimFailure("book"))
        assertTrue(attempts.armOnSuccess(attempt, "book"))
        assertTrue(gate.claimFailure("book"))
    }

    @Test
    fun `only the latest attempt is current`() {
        val first = attempts.begin("book", ExplicitPlaybackAttempts.Kind.RESUME)
        val second = attempts.begin("book", ExplicitPlaybackAttempts.Kind.BOOKMARK)
        assertFalse(attempts.isCurrent(first))
        assertTrue(attempts.isCurrent(second))
    }

    @Test
    fun `a superseded attempt cannot arm recovery`() {
        val first = attempts.begin("book", ExplicitPlaybackAttempts.Kind.RESUME)
        attempts.begin("book", ExplicitPlaybackAttempts.Kind.RESUME)
        assertFalse(attempts.armOnSuccess(first, "book"))
    }

    @Test
    fun `supersede invalidates an in-flight preparation`() {
        val attempt = attempts.begin("book", ExplicitPlaybackAttempts.Kind.RESUME)
        attempts.supersede()
        assertFalse(attempts.isCurrent(attempt))
        assertFalse(attempts.armOnSuccess(attempt, "book"))
    }

    @Test
    fun `the attempt records its kind and the book it was started for`() {
        val attempt = attempts.begin("edition-a", ExplicitPlaybackAttempts.Kind.BOOKMARK)
        assertEquals("edition-a", attempt.bookId)
        assertEquals(ExplicitPlaybackAttempts.Kind.BOOKMARK, attempt.kind)
    }

    @Test
    fun `armOnSuccess arms the installed book, not the started one`() {
        val attempt = attempts.begin("edition-old", ExplicitPlaybackAttempts.Kind.RESUME)
        assertTrue(attempts.armOnSuccess(attempt, "edition-imported"))
        assertTrue(gate.claimFailure("edition-imported"))
        assertFalse(gate.claimFailure("edition-old"))
    }
}
