package com.slukhayka.audiobooks.data.collections

import com.slukhayka.audiobooks.data.source.GlobalSearchResult
import java.io.File
import kotlin.system.measureNanoTime

/** Manual JVM benchmark; run main with the repository root as its argument. */
object RunCollectionMatcherBenchmark {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1) { "Pass the repository root" }
        val assets = File(args.single(), "app/src/main/assets/collections")
        val collections = assets.listFiles().orEmpty().filter { it.extension == "json" }
            .sortedBy { it.name }.map { file ->
                requireNotNull(CollectionJson.decode(file.readText())) { "Invalid collection: $file" }
            }
        val entries = collections.flatMap { it.entries }
        require(entries.isNotEmpty()) { "No collection entries in $assets" }
        var checksum = 0
        for (size in listOf(100, 1000)) {
            // Synthetic library: one fifth of its Works match bundled award
            // claims. This is not a captured listener's library or UI timing.
            val catalog = List(size) { index ->
                val entry = entries[index % entries.size]
                GlobalSearchResult(
                    title = if (index % 5 == 0) entry.title ?: "Книга $index" else "Книга $index",
                    author = if (index % 5 == 0) entry.author else "Інший автор $index",
                    mergeKey = "$index",
                    sources = emptyList()
                )
            }
            fun reference(): List<CollectionMatcher.MatchedCollection> = collections.map { collection ->
                CollectionMatcher.MatchedCollection(collection.id, collection.name, collection.sourceNote,
                    catalog.filter { book -> collection.entries.any { CollectionMatcher.entryMatches(it, book) } })
            }.filter { it.books.isNotEmpty() }
            fun current() = CollectionMatcher.matchAll(collections, catalog)
            check(reference() == current()) { "Results differ for $size Works" }
            repeat(10) { reference(); current() }
            val before = mutableListOf<Double>()
            val after = mutableListOf<Double>()
            fun sample(operation: () -> List<CollectionMatcher.MatchedCollection>): Double =
                measureNanoTime {
                    repeat(3) { checksum += operation().sumOf { it.books.size } }
                } / 3_000_000.0
            repeat(9) { index ->
                if (index % 2 == 0) {
                    before.add(sample(::reference))
                    after.add(sample(::current))
                } else {
                    after.add(sample(::current))
                    before.add(sample(::reference))
                }
            }
            val oldMedian = before.sorted()[before.size / 2]
            val newMedian = after.sorted()[after.size / 2]
            println("syntheticWorks=$size entries=${entries.size} referenceMs=$oldMedian currentMs=$newMedian speedup=${oldMedian / newMedian}")
            println("referenceSamplesMs=$before currentSamplesMs=$after")
        }
        check(checksum > 0)
    }
}
