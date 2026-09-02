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
    fun `direct sources follow the deterministic priority of the web worker SOURCE_PRIORITY`() {
        // #465: within the DIRECT tier the sub-order is fixed —
        // soundbooks → sluhayua → audiobookmp3 → lihtar — mirroring
        // SOURCE_PRIORITY in web/src/worker/workFeed.ts. BROWSER stays last.
        val ordered = SourceAccessPolicy.order(
            listOf(
                SourceAccessCandidate("lihtar", url = "https://lihtar/book"),
                SourceAccessCandidate("sluhay", url = "https://sluhay/book"),
                SourceAccessCandidate("audiobookmp3", url = "https://audiobook-mp3.net/book"),
                SourceAccessCandidate("soundbooks", url = "https://sound-books.net/book"),
                SourceAccessCandidate("4read", url = "https://4read.org/book"),
                SourceAccessCandidate("sluhayua", url = "https://sluhay.com.ua/book"),
                SourceAccessCandidate("legacy", url = "https://legacy/book")
            )
        )
        assertEquals(
            listOf("soundbooks", "sluhayua", "audiobookmp3", "lihtar", "legacy", "4read", "sluhay"),
            ordered.map { it.sourceId }
        )
    }

    @Test
    fun `direct sources missing from the list keep the same relative order`() {
        val ordered = SourceAccessPolicy.order(
            listOf(
                SourceAccessCandidate("lihtar", url = "https://lihtar/book"),
                SourceAccessCandidate("sluhayua", url = "https://sluhay.com.ua/book")
            )
        )
        assertEquals(listOf("sluhayua", "lihtar"), ordered.map { it.sourceId })
    }

    @Test
    fun `direct id unknown to the priority list sorts after the known ones`() {
        // A future adapter declared DIRECT but absent from the deterministic
        // list (the candidate carries its access mode explicitly) falls behind
        // every known direct source, before UNKNOWN and BROWSER tiers.
        val ordered = SourceAccessPolicy.order(
            listOf(
                SourceAccessCandidate(
                    "future-direct",
                    url = "https://future.example/book",
                    accessMode = SourceAccessMode.DIRECT
                ),
                SourceAccessCandidate("lihtar", url = "https://lihtar/book"),
                SourceAccessCandidate("soundbooks", url = "https://sound-books.net/book")
            )
        )
        assertEquals(listOf("soundbooks", "lihtar", "future-direct"), ordered.map { it.sourceId })
    }

    @Test
    fun `4read is explicitly browser backed`() {
        assertEquals(SourceAccessMode.BROWSER, SourceAccessPolicy.modeFor("4read"))
    }

    @Test
    fun `browser-only card needs the explicit browser door`() {
        val needs = SourceAccessPolicy.needsBrowserImport(listOf("4read"))
        assertEquals(true, needs)
    }

    @Test
    fun `card with any direct source does not need the browser door`() {
        val mixed = SourceAccessPolicy.needsBrowserImport(listOf("soundbooks", "4read"))
        val direct = SourceAccessPolicy.needsBrowserImport(listOf("audiobookmp3"))
        val unknown = SourceAccessPolicy.needsBrowserImport(listOf("legacy"))
        val empty = SourceAccessPolicy.needsBrowserImport(emptyList())
        assertEquals(false, mixed)
        assertEquals(false, direct)
        assertEquals(false, unknown)
        assertEquals(false, empty)
    }
}
