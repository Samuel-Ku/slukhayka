package com.slukhayka.audiobooks.data.social

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #896 — the rules of §4 of the social-layer spec. */
class ReportPolicyTest {

    @Test
    fun `one person is one report - a repeat does not inflate the counter`() {
        val once = ReportPolicy.report(ReportState(), "olena")
        val twice = ReportPolicy.report(once, "olena")

        assertEquals(1, once.uniqueAuthors)
        assertEquals(1, twice.uniqueAuthors)
    }

    @Test
    fun `the counter is of unique authors, not of reports`() {
        var state = ReportState()
        listOf("olena", "bohdan", "olena").forEach { state = ReportPolicy.report(state, it) }

        assertEquals(2, state.uniqueAuthors)
    }

    @Test
    fun `the threshold hides the object but never deletes it`() {
        var state = ReportState()
        state = ReportPolicy.report(state, "olena")
        assertFalse("below the threshold nothing is hidden", ReportPolicy.afterThreshold(state, 2).hidden)

        state = ReportPolicy.report(state, "bohdan")
        assertTrue("at the threshold it is hidden", ReportPolicy.afterThreshold(state, 2).hidden)
    }

    @Test
    fun `a report never changes the audience of the object`() {
        assertTrue(ReportPolicy.audienceStaysTheSame(Audience.FRIENDS, Audience.FRIENDS))
        assertFalse(ReportPolicy.audienceStaysTheSame(Audience.FRIENDS, Audience.PUBLIC))
    }

    @Test
    fun `the author is never told who reported them`() {
        assertTrue(
            "the report carries the object, not the reporter",
            ReportPolicy.authorLearns().isEmpty()
        )
    }
}
