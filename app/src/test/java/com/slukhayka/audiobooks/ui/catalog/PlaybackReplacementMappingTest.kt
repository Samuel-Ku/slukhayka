package com.slukhayka.audiobooks.ui.catalog

import com.slukhayka.audiobooks.data.catalog.SourceCatalog
import com.slukhayka.audiobooks.data.catalog.SourceReplacementMapping
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.ChapterEntity
import com.slukhayka.audiobooks.data.db.SourceTrackEntity
import com.slukhayka.audiobooks.data.merge.MergeKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Spec-49 T2b — the player-preparation twin of the card-tap mapping: a
 * refused-only (or sourceless) library book asks the replacement mapping
 * ONCE per touch when the resume/auto-play path finds nothing playable.
 * The resolver owns the request discipline (union-first, memo, one volley);
 * this unit owns the WHEN: never on healthy books, never over an explicit
 * source choice, never twice per call — and a miss or a failure keeps the
 * honest path, never a fabricated book.
 */
class PlaybackReplacementMappingTest {

    private val mergeKey = MergeKey.keyFor("Лісова пісня", "Леся Українка")

    private fun book(id: String = "book-1", mergeKey: String = this.mergeKey) =
        AudiobookEntity(
            id = id,
            title = "Лісова пісня",
            author = "Леся Українка",
            narrator = "",
            description = "",
            coverDrawableRes = 0,
            genre = "",
            sourceUrl = "https://4read.org/book-1"
        ).also { it.mergeKey = mergeKey }

    private fun chapter(index: Int = 0) = ChapterEntity(
        id = "ch-$index",
        bookId = "book-1",
        chapterIndex = index,
        title = "Розділ $index",
        durationSeconds = 60
    )

    private fun paired(chapter: ChapterEntity) = SourceCatalog.PlayableChapter(
        chapter = chapter,
        track = SourceTrackEntity(
            id = "src_tr_${chapter.chapterIndex + 1}",
            sourceId = "src-1",
            trackIndex = chapter.chapterIndex,
            url = "https://sluhay.com.ua/chapter.mp3"
        )
    )

    private fun unpaired(chapter: ChapterEntity) =
        SourceCatalog.PlayableChapter(chapter = chapter, track = null)

    private fun match(sourceId: String = "sluhayua") = SourceReplacementMapping.Match(
        sourceId = sourceId,
        url = "https://sluhay.com.ua/42",
        title = "Лісова пісня",
        author = "Леся Українка",
        narrator = "",
        coverImageUrl = null
    )

    @Test
    fun `healthy book never consults the resolver`() = runTest {
        var resolveCalls = 0
        val mapping = PlaybackReplacementMapping(
            resolve = { _, _, _ -> resolveCalls++; match() },
            importMatch = { _, _ -> error("no import on a healthy book") }
        )

        assertNull(
            mapping.mapIfNeeded(
                book(),
                playable = listOf(paired(chapter())),
                hasPreferredSource = false
            )
        )
        assertEquals(0, resolveCalls)
    }

    @Test
    fun `a refused-only plan with chapters still asks the mapping once`() = runTest {
        // The 4read shape: logical chapters exist but every pair is unpaired
        // (the only Source is refused). The book must search a direct
        // counterpart by itself, not die on «Перевіряємо…».
        var resolveCalls = 0
        val imported = book("book-2")
        val mapping = PlaybackReplacementMapping(
            resolve = { _, _, _ -> resolveCalls++; match() },
            importMatch = { _, _ -> imported }
        )

        assertEquals(
            imported,
            mapping.mapIfNeeded(
                book(),
                playable = listOf(unpaired(chapter(0)), unpaired(chapter(1))),
                hasPreferredSource = false
            )
        )
        assertEquals(1, resolveCalls)
    }

    @Test
    fun `explicit preferred source is respected`() = runTest {
        var resolveCalls = 0
        val mapping = PlaybackReplacementMapping(
            resolve = { _, _, _ -> resolveCalls++; match() },
            importMatch = { _, _ -> error("no import over an explicit choice") }
        )

        assertNull(mapping.mapIfNeeded(book(), playable = emptyList(), hasPreferredSource = true))
        assertEquals(0, resolveCalls)
    }

    @Test
    fun `refused-only book maps once and returns the imported replacement`() = runTest {
        val seen = mutableListOf<Triple<String, String, String>>()
        val imported = book("book-2")
        val notified = mutableListOf<Pair<String, String>>()
        val mapping = PlaybackReplacementMapping(
            resolve = { title, author, key ->
                seen += Triple(title, author, key)
                match()
            },
            importMatch = { _, _ -> imported },
            onMapped = { key, sourceId -> notified += key to sourceId }
        )

        assertEquals(imported, mapping.mapIfNeeded(book(), playable = emptyList(), hasPreferredSource = false))
        assertEquals(listOf(Triple("Лісова пісня", "Леся Українка", mergeKey)), seen)
        assertEquals(listOf(mergeKey to "sluhayua"), notified)
    }

    @Test
    fun `mapping miss keeps the honest path without importing`() = runTest {
        var imports = 0
        var notifications = 0
        val mapping = PlaybackReplacementMapping(
            resolve = { _, _, _ -> null },
            importMatch = { _, _ -> imports++; book("book-2") },
            onMapped = { _, _ -> notifications++ }
        )

        assertNull(mapping.mapIfNeeded(book(), playable = emptyList(), hasPreferredSource = false))
        assertEquals(0, imports)
        assertEquals(0, notifications)
    }

    @Test
    fun `failed import keeps the honest path`() = runTest {
        val mapping = PlaybackReplacementMapping(
            resolve = { _, _, _ -> match() },
            importMatch = { _, _ -> null }
        )

        assertNull(mapping.mapIfNeeded(book(), playable = emptyList(), hasPreferredSource = false))
    }

    @Test
    fun `blank mergeKey falls back to MergeKey dot keyFor`() = runTest {
        var seenKey = ""
        val mapping = PlaybackReplacementMapping(
            resolve = { _, _, key -> seenKey = key; null },
            importMatch = { _, _ -> null }
        )

        mapping.mapIfNeeded(book(mergeKey = ""), playable = emptyList(), hasPreferredSource = false)

        assertEquals(MergeKey.keyFor("Лісова пісня", "Леся Українка"), seenKey)
    }

    @Test
    fun `blank title and author map nowhere`() = runTest {
        var resolveCalls = 0
        val mapping = PlaybackReplacementMapping(
            resolve = { _, _, _ -> resolveCalls++; null },
            importMatch = { _, _ -> null }
        )
        val nameless = AudiobookEntity(
            id = "book-1",
            title = "  ",
            author = "",
            narrator = "",
            description = "",
            coverDrawableRes = 0,
            genre = "",
            sourceUrl = "https://4read.org/book-1"
        )

        assertNull(mapping.mapIfNeeded(nameless, playable = emptyList(), hasPreferredSource = false))
        assertEquals(0, resolveCalls)
    }

    @Test
    fun `resolver failure degrades to the honest path`() = runTest {
        val mapping = PlaybackReplacementMapping(
            resolve = { _, _, _ -> throw IllegalStateException("shared base down") },
            importMatch = { _, _ -> error("no import after a failed resolve") }
        )

        assertNull(mapping.mapIfNeeded(book(), playable = emptyList(), hasPreferredSource = false))
    }

    @Test
    fun `cancellation is rethrown, never swallowed`() = runTest {
        val mapping = PlaybackReplacementMapping(
            resolve = { _, _, _ -> throw CancellationException("gone") },
            importMatch = { _, _ -> error("no import after cancellation") }
        )

        try {
            mapping.mapIfNeeded(book(), playable = emptyList(), hasPreferredSource = false)
            fail("CancellationException must propagate")
        } catch (cancelled: CancellationException) {
            assertTrue(true)
        }
    }
}
