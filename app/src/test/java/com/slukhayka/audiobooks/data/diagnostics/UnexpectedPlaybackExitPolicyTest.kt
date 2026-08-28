package com.slukhayka.audiobooks.data.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnexpectedPlaybackExitPolicyTest {
    @Test
    fun `only a new actionable exit while playback was active is reported`() {
        assertTrue(
            UnexpectedPlaybackExitPolicy.shouldReport(
                reason = ExitReason.LOW_MEMORY,
                playbackWasActive = true,
                exitTimestamp = 200,
                lastReportedTimestamp = 100
            )
        )
        assertFalse(
            UnexpectedPlaybackExitPolicy.shouldReport(
                ExitReason.LOW_MEMORY, playbackWasActive = false, 200, 100
            )
        )
        assertFalse(
            UnexpectedPlaybackExitPolicy.shouldReport(
                ExitReason.USER_REQUESTED, playbackWasActive = true, 200, 100
            )
        )
        assertFalse(
            UnexpectedPlaybackExitPolicy.shouldReport(
                ExitReason.LOW_MEMORY, playbackWasActive = true, 100, 100
            )
        )
    }
}
