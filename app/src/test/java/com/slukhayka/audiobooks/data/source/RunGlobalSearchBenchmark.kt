package com.slukhayka.audiobooks.data.source

import com.slukhayka.audiobooks.data.collections.CollectionJson
import java.io.File
import kotlin.system.measureNanoTime

/** Manual JVM benchmark of search/catalog merging; no requests to Sources. */
object RunGlobalSearchBenchmark {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1) { "Pass the repository root" }
        val assets = File(args.single(), "app/src/main/assets/collections")
        val entries = assets.listFiles().orEmpty().filter { it.extension == "json" }
            .sortedBy { it.name }.flatMap { file ->
                requireNotNull(CollectionJson.decode(file.readText())) { "Invalid collection: $file" }.entries
            }
        require(entries.isNotEmpty()) { "No collection entries in $assets" }
        val sources = listOf("soundbooks", "sluhayua", "audiobookmp3", "lihtar", "librivox", "knigionline")
        var checksum = 0
        // Synthetic source responses: unique search/catalog rows and an
        // overlapping multi-source catalog, using bundled award claims.
        for ((size, workCount) in listOf(200 to 200, 1000 to 1000, 1000 to 100)) {
            val input = List(size) { index ->
                val workIndex = index % workCount
                val entry = entries[workIndex % entries.size]
                SourceBook(
                    title = "${entry.title ?: "Книга"} $workIndex",
                    author = entry.author,
                    narrator = "Читець ${index % 3}",
                    url = "https://example.org/$index",
                    sourceId = sources[index % sources.size],
                    language = if (index % 5 == 0) "en" else "uk"
                )
            }
            repeat(100) { checksum += mergeGlobalSearchResults(input).size }
            val samples = List(9) {
                measureNanoTime {
                    repeat(10) { checksum += mergeGlobalSearchResults(input).size }
                } / 10_000_000.0
            }
            println("cards=$size uniqueWorks=$workCount medianMs=${samples.sorted()[samples.size / 2]} samples=$samples")
        }
        check(checksum > 0)
    }
}
