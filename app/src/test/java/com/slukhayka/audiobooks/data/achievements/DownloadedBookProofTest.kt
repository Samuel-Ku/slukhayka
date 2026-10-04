package com.slukhayka.audiobooks.data.achievements

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadedBookProofTest {
    private fun row(source: String, index: Int, path: String? = "$source$index", type: String = "remote", chapters: Int = 2) =
        DownloadedTrackProof("book", source, type, chapters, index, path)
    @Test fun `only all real files of one source prove a full download`() {
        val ready = setOf("a0", "a1", "b1")
        assertEquals(0L, DownloadedBookProof.count(emptyList()) { it in ready })
        assertEquals(0L, DownloadedBookProof.count(listOf(row("a",0), row("b",1))) { it in ready })
        assertEquals(0L, DownloadedBookProof.count(listOf(row("a",0), row("a",1,"missing"))) { it in ready })
        assertEquals(1L, DownloadedBookProof.count(listOf(row("a",0), row("a",1), row("b",1))) { it in ready })
    }
    @Test fun `local imports empty topology and repeated rows cannot invent downloads`() {
        val ready: (String) -> Boolean = { true }
        assertEquals(0L, DownloadedBookProof.count(listOf(row("a",0,type="local"),row("a",1,type="local")), ready))
        assertEquals(0L, DownloadedBookProof.count(listOf(row("a",0,chapters=0)), ready))
        assertEquals(0L, DownloadedBookProof.count(listOf(row("a",0).copy(isDownloaded=false),row("a",1)), ready))
        assertEquals(0L, DownloadedBookProof.count(listOf(row("a",0),row("a",0)), ready))
        assertEquals(1L, DownloadedBookProof.count(listOf(row("a",0),row("a",1),row("a",1),row("b",0),row("b",1)), ready))
    }
}
