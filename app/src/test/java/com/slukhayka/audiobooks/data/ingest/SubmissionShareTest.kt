package com.slukhayka.audiobooks.data.ingest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Spec-53 T4 — the pure share/clipboard intake: only supported links ever
 * reach the submission flow, and a share that carries "title + URL" is read
 * honestly.
 */
class SubmissionShareTest {

    @Test
    fun `a shared youtube link is extracted from the share text`() {
        assertEquals(
            "https://www.youtube.com/watch?v=abc",
            sharedSubmissionUrlOf("Острів Дума\nhttps://www.youtube.com/watch?v=abc")
        )
        assertEquals(
            "https://youtu.be/abc",
            sharedSubmissionUrlOf("https://youtu.be/abc")
        )
        assertEquals(
            "https://www.youtube.com/playlist?list=PL1",
            sharedSubmissionUrlOf("дивіться https://www.youtube.com/playlist?list=PL1 і все")
        )
    }

    @Test
    fun `a telegram post link is extracted`() {
        assertEquals("https://t.me/bookchannel/42", sharedSubmissionUrlOf("https://t.me/bookchannel/42"))
    }

    @Test
    fun `unsupported or blank text is an honest null`() {
        assertNull(sharedSubmissionUrlOf("просто текст"))
        assertNull(sharedSubmissionUrlOf("https://example.com/book"))
        assertNull(sharedSubmissionUrlOf(null))
        assertNull(sharedSubmissionUrlOf(""))
    }
}
