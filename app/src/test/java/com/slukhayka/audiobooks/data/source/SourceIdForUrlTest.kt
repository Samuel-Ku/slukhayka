package com.slukhayka.audiobooks.data.source

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceIdForUrlTest {
    private val known = listOf(
        "https://sound-books.net/book" to "soundbooks",
        "https://WWW.SOUND-BOOKS.NET/book" to "soundbooks",
        "https://www.sluhay.com.ua/book" to "sluhayua",
        "https://s1.reasd.org/chapter.mp3" to "4read",
        "https://cdn.redirectto.cc/chapter.mp3" to "sluhay"
    )

    @Test
    fun `home and transport hosts retain their source across repeated lookups`() {
        repeat(3) { known.forEach { (url, source) -> assertEquals(url, source, sourceIdForUrl(url)) } }
    }

    @Test
    fun `unknown malformed and blank urls keep their original classifications`() {
        val cases = listOf(
            "" to "local",
            " \t" to "local",
            "not a url" to UNKNOWN_SOURCE_ID,
            "https://" to UNKNOWN_SOURCE_ID,
            "file:///storage/book.m4b" to UNKNOWN_SOURCE_ID,
            "https://unknown.invalid/book" to UNKNOWN_SOURCE_ID,
            "https://sound-books.net.evil.invalid/book" to UNKNOWN_SOURCE_ID,
            "https://evil-sound-books.net/book" to UNKNOWN_SOURCE_ID,
            "https://sound-books.net@evil.invalid/book" to UNKNOWN_SOURCE_ID
        )
        cases.forEach { (url, source) -> assertEquals(url, source, sourceIdForUrl(url)) }
    }

    @Test
    fun `concurrent lookups of different sources return the same classifications`() {
        val pool = Executors.newFixedThreadPool(4)
        try {
            val work = List(8) {
                pool.submit(Callable {
                    repeat(50) { known.forEach { (url, source) -> assertEquals(source, sourceIdForUrl(url)) } }
                })
            }
            work.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }
}
