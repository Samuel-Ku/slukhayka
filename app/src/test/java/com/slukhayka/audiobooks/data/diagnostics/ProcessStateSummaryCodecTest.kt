package com.slukhayka.audiobooks.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessStateSummaryCodecTest {
    @Test
    fun `round trip contains only the five bounded process state values`() {
        val context = CrashContext(
            appVisibility = AppVisibility.BACKGROUND,
            playbackState = DiagnosticPlaybackState.BUFFERING,
            playbackService = DiagnosticPlaybackService.STARTED,
            audioOrigin = DiagnosticAudioOrigin.REMOTE,
            castActive = true
        )

        val encoded = ProcessStateSummaryCodec.encode(context, appVersionCode = 41L, androidApi = 34)

        assertTrue(encoded.size <= 128)
        val decoded = ProcessStateSummaryCodec.decode(encoded)
        assertEquals(context, decoded?.context)
        assertEquals(41L, decoded?.appVersionCode)
        assertEquals(34, decoded?.androidApi)
        assertEquals("v1|41|34|background|buffering|started|remote|true", encoded.decodeToString())
    }

    @Test
    fun `unknown or extra summary values are rejected`() {
        assertNull(ProcessStateSummaryCodec.decode("v1|1|35|background|playing|started|remote|true|extra".encodeToByteArray()))
        assertNull(ProcessStateSummaryCodec.decode("v1|1|35|background|playing|started|secret|true".encodeToByteArray()))
        assertNull(ProcessStateSummaryCodec.decode(ByteArray(129)))
        assertNull(ProcessStateSummaryCodec.decode(byteArrayOf(0xC3.toByte(), 0x28)))
    }
}
