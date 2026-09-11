package com.slukhayka.audiobooks.data.catalog

import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.merge.MergeKey
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ADR-0037 follow-up — the background mapping prewarm: only library Works
 * WITHOUT a direct source are warmed, at most [MappingPrewarm.DEFAULT_LIMIT]
 * per run, one at a time; resolve-only (never an import), best-effort, and a
 * failing Work never stops the run.
 */
class MappingPrewarmTest {

    private fun book(
        id: String,
        title: String,
        author: String,
        sourceUrl: String
    ) = AudiobookEntity(
        id = id,
        title = title,
        author = author,
        narrator = "",
        description = "",
        coverDrawableRes = 0,
        genre = "",
        sourceUrl = sourceUrl
    ).also { it.mergeKey = MergeKey.keyFor(title, author) }

    private fun match() = SourceReplacementMapping.Match(
        sourceId = "sluhayua",
        url = "https://sluhay.com.ua/42",
        title = "Лісова пісня",
        author = "Леся Українка",
        narrator = "",
        coverImageUrl = null
    )

    @Test
    fun `a work with a direct source is skipped`() = runTest {
        val books = listOf(
            book("b1", "Лісова пісня", "Леся Українка", "https://4read.org/book-1"),
            book("b2", "Лісова пісня", "Леся Українка", "https://sluhay.com.ua/42"),
            book("b3", "Кобзар", "Тарас Шевченко", "https://4read.org/book-2")
        )
        val resolved = mutableListOf<String>()
        val prewarm = MappingPrewarm(
            books = { books },
            resolve = { _, _, mergeKey -> resolved += mergeKey; null },
            pauseMillis = {}
        )

        prewarm.runOnce()

        assertEquals(1, resolved.size)
        assertEquals(listOf(book("b3", "Кобзар", "Тарас Шевченко", "https://4read.org/book-2").mergeKey), resolved)
    }

    @Test
    fun `the limit caps the run and pauses only between works`() = runTest {
        val books = (1..7).map { index ->
            book("b$index", "Книга $index", "Автор $index", "https://4read.org/book-$index")
        }
        val pauses = mutableListOf<Long>()
        var calls = 0
        val prewarm = MappingPrewarm(
            books = { books },
            resolve = { _, _, _ -> calls++; match() },
            limit = 3,
            pause = 1_000L,
            pauseMillis = { pauses += it }
        )

        assertEquals(3, prewarm.runOnce())
        assertEquals(3, calls)
        assertEquals(listOf(1_000L, 1_000L), pauses)
    }

    @Test
    fun `a failing work never stops the run`() = runTest {
        val books = (1..3).map { index ->
            book("b$index", "Книга $index", "Автор $index", "https://4read.org/book-$index")
        }
        var calls = 0
        val prewarm = MappingPrewarm(
            books = { books },
            resolve = { _, _, mergeKey ->
                calls++
                if (mergeKey == books[0].mergeKey) error("resolver down") else match()
            },
            pauseMillis = {}
        )

        assertEquals(2, prewarm.runOnce())
        assertEquals(3, calls)
    }

    @Test
    fun `a failing book readout warms nothing`() = runTest {
        var resolveCalls = 0
        val prewarm = MappingPrewarm(
            books = { error("database closed") },
            resolve = { _, _, _ -> resolveCalls++; match() },
            pauseMillis = {}
        )

        assertEquals(0, prewarm.runOnce())
        assertEquals(0, resolveCalls)
    }

    @Test
    fun `every honest verdict is reported with its work`() = runTest {
        val books = listOf(
            book("b1", "Лісова пісня", "Леся Українка", "https://4read.org/book-1"),
            book("b2", "Кобзар", "Тарас Шевченко", "https://4read.org/book-2")
        )
        val reported = mutableListOf<Pair<String, String?>>()
        val prewarm = MappingPrewarm(
            books = { books },
            resolve = { _, _, mergeKey ->
                if (mergeKey == books[0].mergeKey) match() else null
            },
            pauseMillis = {},
            onVerdict = { mergeKey, match -> reported += mergeKey to match?.sourceId }
        )

        prewarm.runOnce()

        assertEquals(
            listOf(books[0].mergeKey to "sluhayua", books[1].mergeKey to null),
            reported
        )
    }

    @Test
    fun `a throwing resolve is not reported as a verdict`() = runTest {
        val books = listOf(
            book("b1", "Лісова пісня", "Леся Українка", "https://4read.org/book-1"),
            book("b2", "Кобзар", "Тарас Шевченко", "https://4read.org/book-2")
        )
        val reported = mutableListOf<String>()
        val prewarm = MappingPrewarm(
            books = { books },
            resolve = { _, _, mergeKey ->
                if (mergeKey == books[0].mergeKey) error("resolver down") else null
            },
            pauseMillis = {},
            onVerdict = { mergeKey, _ -> reported += mergeKey }
        )

        prewarm.runOnce()

        assertEquals(listOf(books[1].mergeKey), reported)
    }
}
