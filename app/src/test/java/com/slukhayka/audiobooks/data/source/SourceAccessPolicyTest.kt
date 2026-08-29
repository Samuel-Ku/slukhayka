package com.slukhayka.audiobooks.data.source

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceAccessPolicyTest {
    @Test
    fun `local copies precede direct unknown and browser sources`() {
        val ordered = SourceAccessPolicy.order(
            listOf(
                SourceAccessCandidate("4read", url = "https://4read.org/book"),
                SourceAccessCandidate("legacy", url = "https://legacy/book"),
                SourceAccessCandidate("soundbooks", url = "https://sound-books.net/book"),
                SourceAccessCandidate("local", url = "/data/book.mp3")
            )
        )
        assertEquals(listOf("local", "soundbooks", "legacy", "4read"), ordered.map { it.sourceId })
    }

    @Test
    fun `same capability is stable by visible name then id and url`() {
        val ordered = SourceAccessPolicy.order(
            listOf(
                SourceAccessCandidate("z", sourceName = "Alpha", url = "https://z"),
                SourceAccessCandidate("a", sourceName = "alpha", url = "https://a"),
                SourceAccessCandidate("m", sourceName = "Beta", url = "https://m")
            )
        )
        assertEquals(listOf("a", "z", "m"), ordered.map { it.sourceId })
    }

    @Test
    fun `4read is explicitly browser backed`() {
        assertEquals(SourceAccessMode.BROWSER, SourceAccessPolicy.modeFor("4read"))
    }
}
