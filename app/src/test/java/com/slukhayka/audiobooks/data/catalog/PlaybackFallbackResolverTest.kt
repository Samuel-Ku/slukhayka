package com.slukhayka.audiobooks.data.catalog

import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.BookRow
import com.slukhayka.audiobooks.data.db.ChapterEntity
import com.slukhayka.audiobooks.data.db.SourceTrackEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #504 — the same-narration direct fallback: the resolver proves the SAME
 * real narration AND the SAME chapter slicing before handing the player a
 * chapter URL. Anything unprovable (blank narrator, mismatched slicing,
 * refused/failed/non-direct source, blank mergeKey) resolves to null, and
 * chapter-list resolutions stay bounded.
 */
class PlaybackFallbackResolverTest {

    private fun row(
        id: String,
        sourceHost: String,
        narrator: String = "Диктор",
        totalChapters: Int = 3,
        mergeKey: String? = "mk1",
    ) = BookRow(
        id = id,
        title = "Книга",
        author = "Автор",
        narrator = narrator,
        description = "",
        coverDrawableRes = 0,
        genre = "",
        sourceUrl = "https://$sourceHost/book",
        totalChapters = totalChapters,
        mergeKey = mergeKey,
    )

    private fun book(narrator: String = "Диктор", mergeKey: String = "mk1") =
        AudiobookEntity(
            id = "self",
            title = "Книга",
            author = "Автор",
            narrator = narrator,
            description = "",
            coverDrawableRes = 0,
            genre = "",
            sourceUrl = "https://4read.org/self",
        ).also { it.mergeKey = mergeKey }

    private fun chapters(bookId: String, sourceId: String, count: Int, host: String) =
        (0 until count).map { i ->
            SourceCatalog.PlayableChapter(
                chapter = ChapterEntity(
                    id = "$bookId-ch$i",
                    bookId = bookId,
                    chapterIndex = i,
                    title = "Розділ ${i + 1}",
                    durationSeconds = 60L,
                ),
                track = SourceTrackEntity(
                    id = "${sourceId}_tr_${i + 1}",
                    sourceId = sourceId,
                    trackIndex = i,
                    url = "https://$host/audio/$i.mp3",
                ),
                sourceId = sourceId,
            )
        }

    private class Fixture(
        val books: List<BookRow>,
        val playable: Map<String, List<SourceCatalog.PlayableChapter>>,
        val refused: Set<String> = emptySet(),
    ) {
        var resolutions = 0
        fun resolver(maxResolutions: Int = 3) = PlaybackFallbackResolver(
            allBooks = { books },
            chaptersFor = { id ->
                resolutions++
                playable[id] ?: emptyList()
            },
            refusedSourceIds = { refused },
            maxResolutions = maxResolutions,
        )
    }

    @Test
    fun `same narration and same slicing resolves the same chapter`() = runTest {
        val f = Fixture(
            books = listOf(row("sb", "sound-books.net")),
            playable = mapOf("sb" to chapters("sb", "soundbooks", 3, "sound-books.net")),
        )
        val match = f.resolver().resolve(book(), chapterCount = 3, chapterIndex = 1, failedSourceId = "4read")
        assertEquals("https://sound-books.net/audio/1.mp3", match?.url)
        assertEquals("soundbooks", match?.sourceId)
    }

    @Test
    fun `different narration never resolves`() = runTest {
        val f = Fixture(
            books = listOf(row("sb", "sound-books.net", narrator = "Інший диктор")),
            playable = mapOf("sb" to chapters("sb", "soundbooks", 3, "sound-books.net")),
        )
        assertNull(f.resolver().resolve(book(), chapterCount = 3, chapterIndex = 1, failedSourceId = "4read"))
        assertEquals("no resolution without a proven narration", 0, f.resolutions)
    }

    @Test
    fun `blank stored narrator is unknown, never evidence`() = runTest {
        val f = Fixture(
            books = listOf(row("sb", "sound-books.net")),
            playable = mapOf("sb" to chapters("sb", "soundbooks", 3, "sound-books.net")),
        )
        assertNull(f.resolver().resolve(book(narrator = ""), chapterCount = 3, chapterIndex = 1, failedSourceId = "4read"))
        assertEquals(0, f.resolutions)
    }

    @Test
    fun `persisted count contradiction skips without a request`() = runTest {
        val f = Fixture(
            books = listOf(row("sb", "sound-books.net", totalChapters = 10)),
            playable = mapOf("sb" to chapters("sb", "soundbooks", 10, "sound-books.net")),
        )
        assertNull(f.resolver().resolve(book(), chapterCount = 3, chapterIndex = 1, failedSourceId = "4read"))
        assertEquals(0, f.resolutions)
    }

    @Test
    fun `resolved count mismatch is not a match`() = runTest {
        val f = Fixture(
            books = listOf(row("sb", "sound-books.net", totalChapters = 0)),
            playable = mapOf("sb" to chapters("sb", "soundbooks", 5, "sound-books.net")),
        )
        assertNull(f.resolver().resolve(book(), chapterCount = 3, chapterIndex = 1, failedSourceId = "4read"))
    }

    @Test
    fun `refused and failed and browser sources are skipped`() = runTest {
        val f = Fixture(
            books = listOf(
                row("r1", "sound-books.net"),
                row("r2", "audiobook-mp3.com"),
                row("r3", "4read.org"),
                row("ok", "sluhay.com.ua"),
            ),
            playable = mapOf("ok" to chapters("ok", "sluhayua", 3, "sluhay.com.ua")),
            refused = setOf("soundbooks"),
        )
        val match = f.resolver().resolve(book(), chapterCount = 3, chapterIndex = 0, failedSourceId = "audiobookmp3")
        assertEquals("sluhayua", match?.sourceId)
    }

    @Test
    fun `direct order wins and resolutions stay bounded`() = runTest {
        val f = Fixture(
            books = listOf(
                row("a", "audiobook-mp3.com"),
                row("s", "sound-books.net"),
            ),
            playable = mapOf(
                "a" to chapters("a", "audiobookmp3", 3, "audiobook-mp3.com"),
                "s" to chapters("s", "soundbooks", 3, "sound-books.net"),
            ),
        )
        val match = f.resolver().resolve(book(), chapterCount = 3, chapterIndex = 2, failedSourceId = "4read")
        assertEquals("sound first per the #465 order", "soundbooks", match?.sourceId)
        assertEquals("first verified match wins, no crawl", 1, f.resolutions)
    }

    @Test
    fun `blank mergeKey resolves nothing`() = runTest {
        val f = Fixture(books = listOf(row("sb", "sound-books.net", mergeKey = null)), playable = emptyMap())
        assertNull(f.resolver().resolve(book(mergeKey = ""), chapterCount = 3, chapterIndex = 0, failedSourceId = "4read"))
        assertEquals(0, f.resolutions)
    }

    @Test
    fun `out-of-range index resolves nothing`() = runTest {
        val f = Fixture(
            books = listOf(row("sb", "sound-books.net")),
            playable = mapOf("sb" to chapters("sb", "soundbooks", 3, "sound-books.net")),
        )
        assertNull(f.resolver().resolve(book(), chapterCount = 3, chapterIndex = 5, failedSourceId = "4read"))
        assertEquals(0, f.resolutions)
    }

    @Test
    fun `self is never a candidate`() = runTest {
        val f = Fixture(books = listOf(row("self", "sound-books.net")), playable = emptyMap())
        assertNull(f.resolver().resolve(book(), chapterCount = 3, chapterIndex = 0, failedSourceId = "4read"))
        assertTrue(f.resolutions == 0)
    }
}
