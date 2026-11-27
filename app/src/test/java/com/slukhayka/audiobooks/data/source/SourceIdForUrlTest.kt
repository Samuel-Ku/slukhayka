package com.slukhayka.audiobooks.data.source

import java.net.URI
import kotlin.system.measureNanoTime
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

/** Manual JVM benchmark against the previous uncached registry-host lookup. */
object RunSourceIdBenchmark {
    private fun host(url: String): String? = try {
        URI(url).host?.lowercase()?.removePrefix("www.")
    } catch (_: Exception) {
        null
    }

    private fun reference(url: String): String {
        if (url.isBlank()) return "local"
        val requested = host(url) ?: return UNKNOWN_SOURCE_ID
        return SourceRegistry.entries.firstOrNull { facts ->
            val hosts = buildSet {
                host(facts.homeUrl)?.let(::add)
                addAll(facts.transportHosts.map { it.lowercase() })
            }
            hosts.any { requested == it || requested.endsWith(".$it") }
        }?.id ?: UNKNOWN_SOURCE_ID
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val urls = SourceRegistry.entries.flatMap { facts ->
            listOfNotNull(facts.homeUrl.takeIf { it.isNotBlank() }) + facts.transportHosts.flatMap { host ->
                listOf("https://$host/book", "https://sub.$host/book", "https://$host.evil.invalid/book")
            }
        } + listOf("https://unknown.invalid/book", "not a url", "")
        urls.forEach { check(reference(it) == sourceIdForUrl(it)) { "Changed source for $it" } }
        var checksum = 0
        fun sample(operation: (String) -> String): Double = measureNanoTime {
            repeat(100) { for (url in urls) checksum += operation(url).hashCode() }
        } / 100_000_000.0
        repeat(3) { sample(::reference); sample(::sourceIdForUrl) }
        val before = mutableListOf<Double>()
        val after = mutableListOf<Double>()
        repeat(9) { index ->
            if (index % 2 == 0) {
                before.add(sample(::reference))
                after.add(sample(::sourceIdForUrl))
            } else {
                after.add(sample(::sourceIdForUrl))
                before.add(sample(::reference))
            }
        }
        val oldMedian = before.sorted()[before.size / 2]
        val newMedian = after.sorted()[after.size / 2]
        println("urls=${urls.size} referenceMs=$oldMedian optimizedMs=$newMedian speedup=${oldMedian / newMedian}")
        println("referenceSamplesMs=$before optimizedSamplesMs=$after checksum=$checksum")
    }
}
