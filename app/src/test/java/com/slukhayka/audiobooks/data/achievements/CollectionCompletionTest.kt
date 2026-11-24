package com.slukhayka.audiobooks.data.achievements

import com.slukhayka.audiobooks.data.collections.*
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class CollectionCompletionTest {
    private fun book(title: String, author: String, finished: Boolean = true) =
        OwnLibraryBook(title, title, title, author, null, null, null, if (finished) 1L else null)
    private fun collection(vararg entries: CollectionEntry) = CollectionList("c", "c", entries = entries.toList())

    @Test
    fun `every entry needs a finished witness with both title and author`() {
        val c = collection(CollectionEntry("A", "one"), CollectionEntry("B", "two"))
        val books = listOf(book("one", "A"), book("two", "B", false), book("two", "A"), book("one", "B"))
        assertEquals(0L, CollectionCompletion.passed(books, listOf(c)))
        assertEquals(1L, CollectionCompletion.passed(books + book("two", "B"), listOf(c)))
    }

    @Test
    fun `cached earlier authors and duplicate entries can reuse the same finished book`() {
        val c = collection(CollectionEntry("B", "two"), CollectionEntry("A"), CollectionEntry("B", "two"))
        assertEquals(2L, CollectionCompletion.passed(listOf(book("one", "A"), book("two", "B")), listOf(c, c)))
        assertEquals(0L, CollectionCompletion.passed(listOf(book("one", "A")), listOf(collection(), collection(CollectionEntry(" ")))))
    }

    @Test
    fun `matches the scalar rule over varied ordering and partial completion`() {
        val random = Random(20261124)
        val authors = listOf("", " ", "!!!", "A", " a ", "Іван Франко", "García")
        val titles = listOf("", " ", "!!!", "one", "ONE!", "Енеїда", "Енеіда")
        repeat(200) {
            val books = List(random.nextInt(31)) { book(titles.random(random), authors.random(random), random.nextBoolean()) }
            val collections = List(random.nextInt(8)) { collection(*List(random.nextInt(8)) {
                CollectionEntry(authors.random(random), if (random.nextBoolean()) null else titles.random(random))
            }.toTypedArray()) }
            val reference = collections.count { c -> c.entries.isNotEmpty() && c.entries.all { entry ->
                books.any { b -> b.completedAt != null && CollectionMatcher.entryMatches(entry, b.title, b.author) }
            } }.toLong()
            assertEquals(reference, CollectionCompletion.passed(books, collections))
            assertEquals(reference, CollectionCompletion.passed(books.reversed(), collections.reversed()))
        }
    }
}
