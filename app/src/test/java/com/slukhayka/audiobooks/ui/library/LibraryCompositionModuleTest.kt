package com.slukhayka.audiobooks.ui.library

import com.slukhayka.audiobooks.data.availability.AvailabilityStatus
import com.slukhayka.audiobooks.data.availability.AvailabilityVerdict
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.ChapterEntity
import com.slukhayka.audiobooks.data.db.PlaybackProgressEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1575 — the library composition seam, driven by flows.
 *
 * No Room, no ViewModel: the module is handed the very flows the production
 * ViewModel passes, so this pins the two projections (unified list + the
 * availability map by mergeKey) and the #1066 "loaded" contract.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryCompositionModuleTest {

    private fun book(
        id: String,
        mergeKey: String = id + "|author",
        sourceUrl: String = "https://sluhay.com/$id",
        isDownloaded: Boolean = false
    ): AudiobookEntity =
        AudiobookEntity(
            id = id,
            title = "Книга $id",
            author = "Автор",
            narrator = "",
            description = "",
            coverDrawableRes = 0,
            genre = "",
            sourceUrl = sourceUrl,
            isDownloaded = isDownloaded,
            totalDurationSeconds = 600L,
            totalChapters = 1
        ).apply { this.mergeKey = mergeKey }

    private fun chapter(bookId: String) = ChapterEntity(
        id = "$bookId-0",
        bookId = bookId,
        chapterIndex = 0,
        title = "Розділ 1",
        durationSeconds = 600L
    )

    private fun progress(bookId: String) = PlaybackProgressEntity(
        editionId = "edition-$bookId",
        bookId = bookId,
        currentChapterIndex = 0,
        currentPositionSeconds = 0L,
        lastListenedAt = 5L,
        isCompleted = false
    )

    private fun module(
        scope: CoroutineScope,
        books: MutableStateFlow<List<AudiobookEntity>>,
        progress: MutableStateFlow<List<PlaybackProgressEntity>> = MutableStateFlow(emptyList()),
        chapters: MutableStateFlow<List<ChapterEntity>> = MutableStateFlow(emptyList()),
        onDevice: MutableStateFlow<Set<String>> = MutableStateFlow(emptySet()),
        verdicts: MutableStateFlow<Map<String, AvailabilityVerdict>> = MutableStateFlow(emptyMap()),
        refused: MutableStateFlow<Set<String>> = MutableStateFlow(emptySet())
    ) = LibraryCompositionModule(
        scope = scope,
        allBooks = books,
        recentProgress = progress,
        allChapters = chapters,
        onDeviceBookIds = onDevice,
        availabilityVerdicts = verdicts,
        refusedSources = refused,
        ioDispatcher = UnconfinedTestDispatcher()
    )

    @Test
    fun `the unified list combines books, progress, chapters and readable ids`() = runTest {
        val books = MutableStateFlow(listOf(book("a")))
        val progressFlow = MutableStateFlow(listOf(progress("a")))
        val chapters = MutableStateFlow(listOf(chapter("a")))
        val onDevice = MutableStateFlow<Set<String>>(emptySet())
        val module = module(CoroutineScope(UnconfinedTestDispatcher(testScheduler)), books, progressFlow, chapters, onDevice)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { module.books.collect {} }

        assertEquals(listOf("a"), module.books.value.map { it.book.id })

        // A later Room update re-projects the same seam.
        books.value = listOf(book("a"), book("b"))
        assertEquals(listOf("a", "b"), module.books.value.map { it.book.id })
    }

    @Test
    fun `isLoaded flips only after the first real list emission`() = runTest {
        val module = module(
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            MutableStateFlow(emptyList())
        )
        runCurrent()
        assertTrue("first emission proves the data was read", module.isLoaded.value)

        val withBook = module(
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            MutableStateFlow(listOf(book("a")))
        )
        runCurrent()
        assertTrue(withBook.isLoaded.value)
    }

    @Test
    fun `availability marks only problem works by mergeKey`() = runTest {
        val available = book("available", mergeKey = "available|author")
        val refused = book("refused", mergeKey = "refused|author", sourceUrl = "https://4read.org/refused")
        val downloaded = book("downloaded", mergeKey = "downloaded|author", isDownloaded = true)
        val verdicts = MutableStateFlow(
            mapOf(
                "available|author" to AvailabilityVerdict(AvailabilityStatus.NOT_FOUND, observedAtMs = 42L)
            )
        )
        val module = module(
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            MutableStateFlow(listOf(available, refused, downloaded)),
            verdicts = verdicts,
            refused = MutableStateFlow(setOf("4read"))
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { module.availability.collect {} }

        val availability = module.availability.value
        assertFalse("a Work whose own source is available stays clean", "available|author" in availability)
        assertEquals(AvailabilityStatus.REFUSED, availability["refused|author"]?.status)
        assertFalse("an offline download always plays", "downloaded|author" in availability)
    }
}
