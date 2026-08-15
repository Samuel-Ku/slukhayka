package com.example.ui.library

import com.example.data.db.AudiobookEntity
import com.example.data.db.ChapterEntity
import com.example.data.db.PlaybackProgressEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM tests for the wayfinder #39 library model (no Robolectric). */
class LibraryModelTest {

    // --- fixtures ----------------------------------------------------------

    private fun book(
        id: String,
        title: String,
        author: String = "Автор",
        sourceUrl: String = "https://4read.org/$id.html",
        isDownloaded: Boolean = false,
        isFavorite: Boolean = false,
        seriesTitle: String? = null,
        seriesIndex: Int? = null,
        totalDurationSeconds: Long = 0L,
        createdAt: Long = 0L
    ) = AudiobookEntity(
        id = id,
        title = title,
        author = author,
        narrator = "Читець",
        description = "",
        coverDrawableRes = 0,
        genre = "",
        sourceUrl = sourceUrl,
        isDownloaded = isDownloaded,
        totalDurationSeconds = totalDurationSeconds,
        isFavorite = isFavorite,
        seriesTitle = seriesTitle,
        seriesIndex = seriesIndex,
        createdAt = createdAt
    )

    private fun chapter(bookId: String, index: Int, duration: Long) = ChapterEntity(
        id = "$bookId-ch$index",
        bookId = bookId,
        chapterIndex = index,
        title = "Глава ${index + 1}",
        durationSeconds = duration
    )

    private fun progress(
        bookId: String,
        chapterIndex: Int = 0,
        position: Long = 0L,
        lastListenedAt: Long = 0L,
        isCompleted: Boolean = false
    ) = PlaybackProgressEntity(
        // ADR-0007: progress is Edition-keyed — one row per rendition.
        editionId = "ed-$bookId",
        bookId = bookId,
        currentChapterIndex = chapterIndex,
        currentPositionSeconds = position,
        lastListenedAt = lastListenedAt,
        isCompleted = isCompleted
    )

    // --- buildLibraryBooks -------------------------------------------------

    @Test
    fun `cumulative position adds previous chapters plus in-chapter offset`() {
        val b = book("a", "Книга")
        val chapters = listOf(chapter("a", 0, 600L), chapter("a", 1, 600L), chapter("a", 2, 600L))
        val p = progress("a", chapterIndex = 1, position = 300L)

        val built = buildLibraryBooks(listOf(b), listOf(p), mapOf("a" to chapters)).single()

        assertEquals(900L, built.cumulativePositionSeconds)
        assertEquals(1800L, built.totalDurationSeconds)
        assertEquals(0.5f, built.percent, 0.0001f)
        assertEquals(900L, built.remainingSeconds)
        assertTrue(built.isListening)
        assertFalse(built.isCompleted)
    }

    // Same root cause as the player bug (2026-08-08): unplayed chapters carry
    // durationSeconds = 0, so summing them under-reports the book. The site
    // total is authoritative; unknown chapters are spread so the card shows
    // the real book length.
    @Test
    fun `authoritative book total wins over the shrunken sum of known chapters`() {
        val b = book("a", "Книга", totalDurationSeconds = 60_071L)
        // Only chapter 0 was played (real duration); the rest unknown.
        val chapters = listOf(chapter("a", 0, 1205L), chapter("a", 1, 0L), chapter("a", 2, 0L))
        val p = progress("a", chapterIndex = 1, position = 452L)

        val built = buildLibraryBooks(listOf(b), listOf(p), mapOf("a" to chapters)).single()

        assertEquals(60_071L, built.totalDurationSeconds)
        // Position: known ch0 (1205) + in-chapter offset (452).
        assertEquals(1205L + 452L, built.cumulativePositionSeconds)
        assertTrue(built.percent in 0f..1f)
        assertTrue(built.isListening)
    }

    @Test
    fun `total duration falls back to the book stamp when there are no chapters`() {
        val b = book("a", "Книга", totalDurationSeconds = 3600L)
        val p = progress("a", position = 900L)

        val built = buildLibraryBooks(listOf(b), listOf(p), emptyMap()).single()

        assertEquals(3600L, built.totalDurationSeconds)
        assertEquals(0.25f, built.percent, 0.0001f)
        assertEquals(2700L, built.remainingSeconds)
    }

    @Test
    fun `no progress means zero position and not listening`() {
        val b = book("a", "Книга")
        val built = buildLibraryBooks(listOf(b), emptyList(), emptyMap()).single()

        assertEquals(0L, built.cumulativePositionSeconds)
        assertEquals(0f, built.percent, 0.0001f)
        assertFalse(built.isListening)
        assertFalse(built.isCompleted)
        assertEquals(0L, built.lastListenedAt)
    }

    @Test
    fun `completed progress or position at the end marks the book completed`() {
        val b = book("a", "Книга")
        val chapters = listOf(chapter("a", 0, 600L))

        val flagCompleted = buildLibraryBooks(
            listOf(b), listOf(progress("a", isCompleted = true)), mapOf("a" to chapters)
        ).single()
        assertTrue(flagCompleted.isCompleted)
        assertFalse(flagCompleted.isListening)

        val positionAtEnd = buildLibraryBooks(
            listOf(b), listOf(progress("a", position = 600L)), mapOf("a" to chapters)
        ).single()
        assertTrue(positionAtEnd.isCompleted)
    }

    @Test
    fun `local and online books are told apart by the source url`() {
        val local = buildLibraryBooks(listOf(book("l", "Локальна", sourceUrl = "")), emptyList(), emptyMap()).single()
        val online = buildLibraryBooks(listOf(book("o", "Онлайн")), emptyList(), emptyMap()).single()

        assertTrue(local.isLocal)
        assertFalse(online.isLocal)
    }

    // --- spec-15 T6: the multi-source badge and the online chip -------------

    @Test
    fun `source badge shows the real source not a hardcoded 4read`() {
        val sluhay = buildLibraryBooks(
            listOf(book("s", "Пасажир", sourceUrl = "https://sluhay.com/pasazhir.html")), emptyList(), emptyMap()
        ).single()
        val soundbooks = buildLibraryBooks(
            listOf(book("sb", "Темна матерія", sourceUrl = "https://sound-books.net/x.html")), emptyList(), emptyMap()
        ).single()
        val fourRead = buildLibraryBooks(
            listOf(book("4", "Кобзар")), emptyList(), emptyMap()
        ).single()
        val local = buildLibraryBooks(
            listOf(book("l", "Локальна", sourceUrl = "")), emptyList(), emptyMap()
        ).single()

        assertEquals("Sluhay", sluhay.sourceName)
        assertEquals("Sound-Books", soundbooks.sourceName)
        assertEquals("4read", fourRead.sourceName)
        assertEquals("Локальна", local.sourceName)
    }

    @Test
    fun `online filter means any source and its chip label says so`() {
        // The filter selects every non-local book regardless of the source.
        val items = buildLibraryBooks(
            listOf(
                book("local", "Локальна", sourceUrl = ""),
                book("sluhay", "Пасажир", sourceUrl = "https://sluhay.com/pasazhir.html"),
                book("soundbooks", "Темна матерія", sourceUrl = "https://sound-books.net/x.html"),
                book("lihtar", "Слово", sourceUrl = "https://lihtar.in.ua/biblioteka/slovo")
            ),
            emptyList(), emptyMap()
        )

        assertEquals(
            setOf("sluhay", "soundbooks", "lihtar"),
            idSet(filterAndSortLibrary(items, LibraryFilter.ONLINE, LibrarySort.TITLE, ""))
        )
        // Spec-15 T6: the chip says «Онлайн», not the dated «4read».
        assertEquals("Онлайн", LibraryFilter.ONLINE.label)
    }

    @Test
    fun `series label combines title and volume`() {
        val withVolume = buildLibraryBooks(
            listOf(book("a", "Книга", seriesTitle = "Сага", seriesIndex = 2)), emptyList(), emptyMap()
        ).single()
        val noVolume = buildLibraryBooks(
            listOf(book("b", "Книга", seriesTitle = "Сага", seriesIndex = null)), emptyList(), emptyMap()
        ).single()
        val none = buildLibraryBooks(listOf(book("c", "Книга")), emptyList(), emptyMap()).single()

        assertEquals("Сага · Книга 2", withVolume.seriesLabel)
        assertEquals("Сага", noVolume.seriesLabel)
        assertNull(none.seriesLabel)
    }

    // --- filters -----------------------------------------------------------

    private val filterBooks = listOf(
        book("local", "Альфа", sourceUrl = "", isDownloaded = true, isFavorite = true,
            seriesTitle = "Серія", createdAt = 100L),
        book("online-b", "Бета", author = "Андрій", createdAt = 200L),
        book("online-c", "Гамма", createdAt = 300L)
    )
    private val filterItems = buildLibraryBooks(
        filterBooks,
        listOf(
            progress("local", position = 120L, lastListenedAt = 100L),
            progress("online-c", isCompleted = true)
        ),
        emptyMap()
    )

    @Test
    fun `every quick filter selects exactly its set`() {
        assertEquals(setOf("local", "online-b", "online-c"), idSet(filterAndSortLibrary(filterItems, LibraryFilter.ALL, LibrarySort.TITLE, "")))
        assertEquals(setOf("local"), idSet(filterAndSortLibrary(filterItems, LibraryFilter.LISTENING, LibrarySort.TITLE, "")))
        assertEquals(setOf("online-c"), idSet(filterAndSortLibrary(filterItems, LibraryFilter.COMPLETED, LibrarySort.TITLE, "")))
        assertEquals(setOf("local"), idSet(filterAndSortLibrary(filterItems, LibraryFilter.DOWNLOADED, LibrarySort.TITLE, "")))
        assertEquals(setOf("local"), idSet(filterAndSortLibrary(filterItems, LibraryFilter.LOCAL, LibrarySort.TITLE, "")))
        assertEquals(setOf("online-b", "online-c"), idSet(filterAndSortLibrary(filterItems, LibraryFilter.ONLINE, LibrarySort.TITLE, "")))
        assertEquals(setOf("local"), idSet(filterAndSortLibrary(filterItems, LibraryFilter.FAVORITE, LibrarySort.TITLE, "")))
    }

    @Test
    fun `search matches title author and series case-insensitively`() {
        assertEquals(setOf("local"), idSet(filterAndSortLibrary(filterItems, LibraryFilter.ALL, LibrarySort.TITLE, "альфа")))
        assertEquals(setOf("online-b"), idSet(filterAndSortLibrary(filterItems, LibraryFilter.ALL, LibrarySort.TITLE, "андрій")))
        assertEquals(setOf("local"), idSet(filterAndSortLibrary(filterItems, LibraryFilter.ALL, LibrarySort.TITLE, "СЕРІЯ")))
        assertEquals(emptySet<String>(), idSet(filterAndSortLibrary(filterItems, LibraryFilter.ALL, LibrarySort.TITLE, "неіснує")))
    }

    @Test
    fun `search and filter compose`() {
        // Search narrows the downloaded set to the local book only.
        assertEquals(
            setOf("local"),
            idSet(filterAndSortLibrary(filterItems, LibraryFilter.DOWNLOADED, LibrarySort.TITLE, "альфа"))
        )
    }

    // --- sorting -----------------------------------------------------------

    @Test
    fun `title sort is alphabetical`() {
        val items = listOf("c", "a", "b").map { buildLibraryBooks(listOf(book(it, it)), emptyList(), emptyMap()).single() }
        assertEquals(listOf("a", "b", "c"), ids(filterAndSortLibrary(items, LibraryFilter.ALL, LibrarySort.TITLE, "")))
    }

    @Test
    fun `author sort puts blank authors last and sorts the rest`() {
        val items = listOf(
            buildLibraryBooks(listOf(book("noauthor", "Книга", author = "")), emptyList(), emptyMap()).single(),
            buildLibraryBooks(listOf(book("z", "Зета", author = "Зоя")), emptyList(), emptyMap()).single(),
            buildLibraryBooks(listOf(book("a", "Альфа", author = "Андрій")), emptyList(), emptyMap()).single()
        )
        assertEquals(listOf("a", "z", "noauthor"), ids(filterAndSortLibrary(items, LibraryFilter.ALL, LibrarySort.AUTHOR, "")))
    }

    @Test
    fun `recently listened sorts by last listen descending with never-listened last`() {
        val items = listOf(
            buildLibraryBooks(listOf(book("never", "Ніколи")), emptyList(), emptyMap()).single(),
            buildLibraryBooks(listOf(book("old", "Старе")), listOf(progress("old", lastListenedAt = 100L)), emptyMap()).single(),
            buildLibraryBooks(listOf(book("new", "Нове")), listOf(progress("new", lastListenedAt = 300L)), emptyMap()).single()
        )
        assertEquals(listOf("new", "old", "never"), ids(filterAndSortLibrary(items, LibraryFilter.ALL, LibrarySort.RECENTLY_LISTENED, "")))
    }

    @Test
    fun `recently added sorts by creation time descending`() {
        val items = listOf(
            buildLibraryBooks(listOf(book("old", "Старе", createdAt = 100L)), emptyList(), emptyMap()).single(),
            buildLibraryBooks(listOf(book("new", "Нове", createdAt = 300L)), emptyList(), emptyMap()).single()
        )
        assertEquals(listOf("new", "old"), ids(filterAndSortLibrary(items, LibraryFilter.ALL, LibrarySort.RECENTLY_ADDED, "")))
    }

    @Test
    fun `progress sort ascends from not started to finished`() {
        val half = buildLibraryBooks(
            listOf(book("half", "Половина")),
            listOf(progress("half", position = 300L)),
            mapOf("half" to listOf(chapter("half", 0, 600L)))
        ).single()
        val none = buildLibraryBooks(listOf(book("none", "Нуль")), emptyList(), emptyMap()).single()
        val done = buildLibraryBooks(
            listOf(book("done", "Готово")),
            listOf(progress("done", position = 600L)),
            mapOf("done" to listOf(chapter("done", 0, 600L)))
        ).single()

        assertEquals(
            listOf("none", "half", "done"),
            ids(filterAndSortLibrary(listOf(done, none, half), LibraryFilter.ALL, LibrarySort.PROGRESS, ""))
        )
    }

    @Test
    fun `duration sort goes longest first`() {
        val items = listOf(
            buildLibraryBooks(listOf(book("short", "Коротка", totalDurationSeconds = 600L)), emptyList(), emptyMap()).single(),
            buildLibraryBooks(listOf(book("long", "Довга", totalDurationSeconds = 7200L)), emptyList(), emptyMap()).single()
        )
        assertEquals(listOf("long", "short"), ids(filterAndSortLibrary(items, LibraryFilter.ALL, LibrarySort.DURATION, "")))
    }

    @Test
    fun `title breaks ties deterministically`() {
        // Two books at the same progress — the secondary title sort decides.
        val items = listOf(
            buildLibraryBooks(listOf(book("b", "Бета")), emptyList(), emptyMap()).single(),
            buildLibraryBooks(listOf(book("a", "Альфа")), emptyList(), emptyMap()).single()
        )
        assertEquals(listOf("a", "b"), ids(filterAndSortLibrary(items, LibraryFilter.ALL, LibrarySort.PROGRESS, "")))
    }

    // --- formatRemainingTime ------------------------------------------------

    @Test
    fun `remaining time formats hours and minutes`() {
        assertEquals("—", formatRemainingTime(0L))
        assertEquals("—", formatRemainingTime(-5L))
        assertEquals("1 хв", formatRemainingTime(45L))
        assertEquals("45 хв", formatRemainingTime(45 * 60L))
        assertEquals("4 год 12 хв", formatRemainingTime(4 * 3600L + 12 * 60L))
        assertEquals("4 год", formatRemainingTime(4 * 3600L))
    }

    // --- ADR-0007: multiple progress rows (one per Edition) dedup to one card

    @Test
    fun `per-edition progress rows collapse into one card with the latest`() {
        val b = book("b", "Бета")
        val progress = listOf(
            progress("b", chapterIndex = 0, position = 10L, lastListenedAt = 100L).copy(editionId = "ed-soundbooks"),
            progress("b", chapterIndex = 0, position = 400L, lastListenedAt = 300L).copy(editionId = "ed-audiobookmp3")
        )

        val cards = buildLibraryBooks(listOf(b), progress, emptyMap())

        assertEquals(1, cards.size)
        // The card reflects the latest-listened rendition row.
        assertEquals(400L, cards.single().progress?.currentPositionSeconds)
        assertEquals(300L, cards.single().lastListenedAt)
    }

    // --- helpers ------------------------------------------------------------

    private fun ids(items: List<LibraryBook>): List<String> = items.map { it.book.id }

    private fun idSet(items: List<LibraryBook>): Set<String> = ids(items).toSet()
}
