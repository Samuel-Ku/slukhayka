package com.slukhayka.audiobooks.data.achievements

import com.slukhayka.audiobooks.data.collections.*
import java.io.File
import kotlin.system.measureNanoTime

/** Manual host JVM benchmark; the repository root is the first argument. */
object RunCollectionCompletionBenchmark {
    @JvmStatic
    fun main(args: Array<String>) {
        val collections = File(args.single(), "app/src/main/assets/collections").listFiles()!!
            .filter { it.extension == "json" }.sortedBy { it.name }.map { requireNotNull(CollectionJson.decode(it.readText())) }
        val entries = collections.flatMap { it.entries }.distinctBy { it.author to it.title }
        fun book(i: Int, finished: Boolean = true): OwnLibraryBook {
            val e = entries[i % entries.size]
            return OwnLibraryBook("b$i", "w$i", e.title ?: "книга $i", e.author, null, null, null, if (finished) 1L else null)
        }
        fun reference(books: List<OwnLibraryBook>, lists: List<CollectionList>) = lists.count { c ->
            c.entries.isNotEmpty() && c.entries.all { e -> books.any { b ->
                b.completedAt != null && CollectionMatcher.entryMatches(e, b.title, b.author)
            } }
        }.toLong()
        val firstBookCollections = List(3) { CollectionList("$it", "first", entries = List(20) { CollectionEntry("автор") }) }
        val scenarios = listOf(
            Triple("assets/1", listOf(book(0)), collections),
            Triple("assets/1000", List(1000) { book(it) }, collections),
            Triple("unfinished/1000", List(1000) { book(it, false) }, collections),
            Triple("missing/1000", List(1000) { book(it).copy(author = "інший автор") }, collections),
            Triple("first-witness/1000", List(1000) { book(it).copy(author = "автор") }, firstBookCollections)
        )
        var checksum = 0L
        for ((name, books, lists) in scenarios) {
            check(reference(books, lists) == CollectionCompletion.passed(books, lists))
            fun sample(current: Boolean): Long = measureNanoTime { repeat(20) { checksum +=
                if (current) CollectionCompletion.passed(books, lists) else reference(books, lists)
            } }
            repeat(30) { sample(false); sample(true) }
            val before = mutableListOf<Long>(); val after = mutableListOf<Long>()
            repeat(9) { i -> if (i % 2 == 0) { before += sample(false); after += sample(true) }
                else { after += sample(true); before += sample(false) } }
            println("$name reference=$before candidate=$after")
            println("$name ms/call reference=${before.sorted()[4] / 20e6} candidate=${after.sorted()[4] / 20e6}")
        }
        println("checksum=$checksum")
    }
}
