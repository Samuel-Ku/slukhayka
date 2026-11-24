package com.slukhayka.audiobooks.data.merge

import com.slukhayka.audiobooks.data.collections.CollectionJson
import java.io.File
import kotlin.system.measureNanoTime

/** Manual JVM comparison with the previous list/filter/join implementation. */
object RunMergeKeyBenchmark {
    private fun reference(title: String, author: String): String {
        val base = listOf(MergeKey.normalizeTitle(title), MergeKey.normalizePerson(author))
            .filter { it.isNotBlank() }
        return if (base.size < 2) "" else base.joinToString("|")
    }

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1) { "Pass the repository root" }
        val entries = File(args.single(), "app/src/main/assets/collections")
            .listFiles().orEmpty().filter { it.extension == "json" }.sortedBy { it.name }
            .flatMap { file -> requireNotNull(CollectionJson.decode(file.readText())).entries }
        require(entries.isNotEmpty()) { "No collection entries" }
        val inputs = List(1000) { index ->
            val entry = entries[index % entries.size]
            "${entry.title ?: "Книга"} $index" to entry.author
        }
        inputs.forEach { (title, author) -> check(reference(title, author) == MergeKey.keyFor(title, author)) }
        var checksum = 0
        fun sample(operation: (String, String) -> String): Double = measureNanoTime {
            repeat(50) {
                for ((title, author) in inputs) checksum += operation(title, author).hashCode()
            }
        } / 50_000_000.0
        repeat(10) { sample(::reference); sample(MergeKey::keyFor) }
        val before = mutableListOf<Double>()
        val after = mutableListOf<Double>()
        repeat(9) { index ->
            if (index % 2 == 0) {
                before.add(sample(::reference))
                after.add(sample(MergeKey::keyFor))
            } else {
                after.add(sample(MergeKey::keyFor))
                before.add(sample(::reference))
            }
        }
        val oldMedian = before.sorted()[before.size / 2]
        val newMedian = after.sorted()[after.size / 2]
        println("keys=${inputs.size} referenceMs=$oldMedian optimizedMs=$newMedian speedup=${oldMedian / newMedian}")
        println("referenceSamplesMs=$before optimizedSamplesMs=$after checksum=$checksum")
    }
}
