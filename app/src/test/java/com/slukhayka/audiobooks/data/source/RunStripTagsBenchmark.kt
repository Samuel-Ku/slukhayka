package com.slukhayka.audiobooks.data.source

import java.io.File
import kotlin.system.measureNanoTime

/** Manual fixture-fragment benchmark; pass the repository root as the first argument. */
object RunStripTagsBenchmark {
    @JvmStatic
    fun main(args: Array<String>) {
        val directory = File(args.single(), "app/src/test/resources/fixtures")
        val inputs = directory.listFiles()!!.filter { it.extension == "html" }.sortedBy { it.name }
            .flatMap { it.readLines().filter { line -> '<' in line && line.length <= 1000 }.take(500) }
        check(inputs.isNotEmpty())
        fun reference(input: String, replacement: String) = Regex("""<[^>]+>""").replace(input, replacement)
        for (input in inputs) for (replacement in listOf("", " ", "\n", "$0")) {
            check(reference(input, replacement) == stripTags(input, replacement))
        }
        var checksum = 0L
        fun sample(current: Boolean): Long = measureNanoTime {
            repeat(50) { for (input in inputs) checksum +=
                (if (current) stripTags(input, " ") else reference(input, " ")).hashCode()
            }
        }
        repeat(30) { sample(false); sample(true) }
        val before = mutableListOf<Long>(); val after = mutableListOf<Long>()
        repeat(9) { i ->
            if (i % 2 == 0) { before += sample(false); after += sample(true) }
            else { after += sample(true); before += sample(false) }
        }
        println("fixtureFragments=${inputs.size}")
        println("reference ns/50 batches=$before")
        println("cached ns/50 batches=$after")
        println("ms/batch reference=${before.sorted()[4] / 50e6} cached=${after.sorted()[4] / 50e6} checksum=$checksum")
    }
}
