package com.slukhayka.audiobooks.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class UnexpectedExitPolicyTest {

    @Test
    fun `low-memory exit while playback was active becomes a bounded report`() {
        val event = UnexpectedExitPolicy.classify(
            ProcessExitSnapshot(
                timestampMillis = 1_725_000_123_456L,
                reason = ProcessExitReason.LOW_MEMORY,
                status = 9,
                importance = ProcessImportance.FOREGROUND_SERVICE,
                rssKb = 131_072L,
                pssKb = 65_536L,
                appVersionCode = 7L,
                androidApi = 35,
                state = CrashContext(
                    appVisibility = AppVisibility.BACKGROUND,
                    playbackState = DiagnosticPlaybackState.PLAYING,
                    playbackService = DiagnosticPlaybackService.STARTED,
                    audioOrigin = DiagnosticAudioOrigin.REMOTE,
                    castActive = false
                )
            )
        )

        assertNotNull(event)
        assertEquals(ProcessExitReason.LOW_MEMORY, event?.reason)
        assertEquals(9, event?.status)
        assertEquals(ProcessImportance.FOREGROUND_SERVICE, event?.importance)
        assertEquals(131_072L, event?.rssKb)
        assertEquals(65_536L, event?.pssKb)
        assertEquals(7L, event?.appVersionCode)
        assertEquals(35, event?.androidApi)
        assertEquals("playing", event?.state?.playbackState?.wireValue)
    }

    @Test
    fun `only actionable OS resource and signal reasons pass the policy`() {
        val actionable = listOf(
            ProcessExitReason.SIGNALED,
            ProcessExitReason.LOW_MEMORY,
            ProcessExitReason.EXCESSIVE_RESOURCE_USAGE,
            ProcessExitReason.DEPENDENCY_DIED
        )
        val excluded = ProcessExitReason.entries - actionable.toSet()

        actionable.forEach { reason ->
            assertNotNull("$reason should be actionable", UnexpectedExitPolicy.classify(exit(reason)))
        }
        excluded.forEach { reason ->
            assertNull("$reason should be excluded", UnexpectedExitPolicy.classify(exit(reason)))
        }
    }

    @Test
    fun `playback gate accepts playing or buffering and rejects inactive state`() {
        assertNotNull(UnexpectedExitPolicy.classify(exit(playback = DiagnosticPlaybackState.PLAYING)))
        assertNotNull(UnexpectedExitPolicy.classify(exit(playback = DiagnosticPlaybackState.BUFFERING)))
        assertNull(UnexpectedExitPolicy.classify(exit(playback = DiagnosticPlaybackState.PAUSED)))
        assertNull(UnexpectedExitPolicy.classify(exit(playback = DiagnosticPlaybackState.IDLE)))
        assertNull(UnexpectedExitPolicy.classify(exit(androidApi = 29)))
    }

    private fun exit(
        reason: ProcessExitReason = ProcessExitReason.LOW_MEMORY,
        playback: DiagnosticPlaybackState = DiagnosticPlaybackState.PLAYING,
        androidApi: Int = 35
    ) = ProcessExitSnapshot(
        timestampMillis = 1_725_000_123_456L,
        reason = reason,
        status = 9,
        importance = ProcessImportance.FOREGROUND_SERVICE,
        rssKb = 131_072L,
        pssKb = 65_536L,
        appVersionCode = 7L,
        androidApi = androidApi,
        state = CrashContext(
            appVisibility = AppVisibility.BACKGROUND,
            playbackState = playback,
            playbackService = DiagnosticPlaybackService.STARTED,
            audioOrigin = DiagnosticAudioOrigin.REMOTE,
            castActive = false
        )
    )
}
