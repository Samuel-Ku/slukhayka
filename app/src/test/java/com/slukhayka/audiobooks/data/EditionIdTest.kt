package com.slukhayka.audiobooks.data

import kotlin.system.measureNanoTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Pure tests for the deterministic Edition id (ADR-0007 + ADR-0010).
 *
 * ADR-0010 — the narrator is part of the edition id because the Work key no
 * longer carries it: two narrations of one Work must resolve to DIFFERENT
 * editions, or they would share listening state (ADR-0001 — incompatible
 * narrations never share timestamps).
 */
class EditionIdTest {

    @Test
    fun `same work and narrator and language - same edition`() {
        assertEquals(
            EditionId.forBook("кобзар|тарас шевченко", "b1", "Валерій Завалко"),
            EditionId.forBook("кобзар|тарас шевченко", "b1", "Валерій Завалко")
        )
    }

    @Test
    fun `different narrators of the same work - different editions`() {
        // ADR-0010 + ADR-0001: the narrator is a rendition property — two
        // narrations of the same text keep separate editions and thus
        // separate progress/bookmarks.
        assertNotEquals(
            EditionId.forBook("кобзар|тарас шевченко", "b1", "Валерій Завалко"),
            EditionId.forBook("кобзар|тарас шевченко", "b1", "Богдан Бенюк")
        )
    }

    @Test
    fun `narrator-free and narrator-ful books differ when narrators differ`() {
        assertNotEquals(
            EditionId.forBook("кобзар|тарас шевченко", "b1", ""),
            EditionId.forBook("кобзар|тарас шевченко", "b1", "Валерій Завалко")
        )
    }

    @Test
    fun `blank key falls back to the book id and still carries the narrator`() {
        assertNotEquals(
            EditionId.forBook("", "local-1"),
            EditionId.forBook("", "local-1", "Локальний аудіофайл")
        )
    }

    @Test
    fun `language keeps editions apart`() {
        assertNotEquals(
            EditionId.forBook("кобзар|тарас шевченко", "b1", "Валерій Завалко", ""),
            EditionId.forBook("кобзар|тарас шевченко", "b1", "Валерій Завалко", "uk")
        )
    }

    @Test
    fun `the legacy formula differs from the ADR-0010 formula`() {
        // The v13→v14-era id hashed the narrator INSIDE the mergeKey; the
        // ADR-0010 id hashes it as a separate input — the v16 migration
        // remaps every legacy id to the new one.
        assertNotEquals(
            EditionId.forBook("кобзар|тарас шевченко|валерій завалко", "b1"),
            EditionId.forBookLegacy("кобзар|тарас шевченко|валерій завалко", "b1")
        )
    }
    @Test
    fun `both formulas retain the published SHA256 prefix`() {
        val values = listOf("", " ", "кобзар|тарас шевченко", "narrator 🎧", "a\u0000b", "ї", "\uD800")
        for (key in values) for (id in values) for (narrator in values) for (language in values) {
            fun reference(text: String): String = java.security.MessageDigest.getInstance("SHA-256")
                .digest(text.toByteArray(Charsets.UTF_8))
                .take(12).joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(reference("${key.ifBlank { id }}\u0000$narrator\u0000$language"),
                EditionId.forBook(key, id, narrator, language))
            assertEquals(reference("${key.ifBlank { id }}\u0000$language"),
                EditionId.forBookLegacy(key, id, language))
        }
    }

    @Test
    fun `full digest and content hashing keep all bytes`() {
        val bytes = "abc".toByteArray()
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256Hex(bytes))
        val firstDigest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        assertEquals(sha256Hex(firstDigest), contentHashOf(bytes.inputStream()))
    }
}

/** Manual host JVM probe: compares the historical full-hex path to the prefix path. */
object RunEditionIdBenchmark {
    private val hex = "0123456789abcdef".toCharArray()
    @JvmStatic
    fun main(args: Array<String>) {
        val inputs = List(1000) { i -> arrayOf("назва $i|автор ${i % 33}", "book-$i", "читач ${i % 7}", "uk") }
        fun fullHex(bytes: ByteArray): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            val sb = StringBuilder(digest.size * 2)
            for (b in digest) {
                val v = b.toInt() and 255
                sb.append(hex[v ushr 4]).append(hex[v and 15])
            }
            return sb.toString()
        }
        fun reference(a: Array<String>) = fullHex("${a[0].ifBlank { a[1] }}\u0000${a[2]}\u0000${a[3]}".toByteArray(Charsets.UTF_8)).take(24)
        fun candidate(a: Array<String>) = EditionId.forBook(a[0], a[1], a[2], a[3])
        check(inputs.all { reference(it) == candidate(it) })
        var checksum = 0L
        fun sample(current: Boolean): Long = measureNanoTime {
            repeat(50) { for (a in inputs) checksum += (if (current) candidate(a) else reference(a)).hashCode() }
        }
        repeat(30) { sample(false); sample(true) }
        val before = mutableListOf<Long>(); val after = mutableListOf<Long>()
        repeat(9) { i ->
            if (i % 2 == 0) { before += sample(false); after += sample(true) }
            else { after += sample(true); before += sample(false) }
        }
        println("reference ns/50x1000=$before")
        println("prefix ns/50x1000=$after")
        println("ms/1000 reference=${before.sorted()[4] / 50e6} prefix=${after.sorted()[4] / 50e6} checksum=$checksum")
    }
}
