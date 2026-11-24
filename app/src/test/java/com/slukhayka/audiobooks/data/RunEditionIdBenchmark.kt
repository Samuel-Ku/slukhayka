package com.slukhayka.audiobooks.data

import kotlin.system.measureNanoTime

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
