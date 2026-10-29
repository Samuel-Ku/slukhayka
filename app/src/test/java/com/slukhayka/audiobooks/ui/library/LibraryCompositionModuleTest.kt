package com.slukhayka.audiobooks.ui.library

import com.slukhayka.audiobooks.data.availability.AvailabilityStatus
import com.slukhayka.audiobooks.data.availability.AvailabilityVerdict
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.ChapterEntity
import com.slukhayka.audiobooks.data.db.PlaybackProgressEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
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
 * availability map by mergeKey) and the #1066 "loaded" contract — including
 * the NEGATIVE half: the flag must stay false until the book rows arrive.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryCompositionModuleTest {

    private fun book(
        id: String,
        mergeKey: String = id + "|author",
        sourceUrl: String = "https://sluhay.com/$id",
        isDownloaded: Boolean = false,
        totalDurationSeconds: Long = 600L
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
            totalDurationSeconds = totalDurationSeconds,
            totalChapters = 1
        ).apply { this.mergeKey = mergeKey }

    private fun chapter(bookId: String, durationSeconds: Long = 600L) = ChapterEntity(
        id = "$bookId-0",
        bookId = bookId,
        chapterIndex = 0,
        title = "Розділ 1",
        durationSeconds = durationSeconds
    )

    private fun progress(bookId: String, positionSeconds: Long = 120L) = PlaybackProgressEntity(
        editionId = "edition-$bookId",
        bookId = bookId,
        currentChapterIndex = 0,
        currentPositionSeconds = positionSeconds,
        lastListenedAt = 5L,
        isCompleted = false
    )

    private fun module(
        scope: CoroutineScope,
        books: Flow<List<AudiobookEntity>>,
        progress: Flow<List<PlaybackProgressEntity>> = MutableStateFlow(emptyList()),
        chapters: Flow<List<ChapterEntity>> = MutableStateFlow(emptyList()),
        onDevice: Flow<Set<String>> = MutableStateFlow(emptySet()),
        verdicts: Flow<Map<String, AvailabilityVerdict>> = MutableStateFlow(emptyMap()),
        refused: Flow<Set<String>> = MutableStateFlow(emptySet())
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
        // Unknown site total: the chapter is the ONLY proof of duration, so a
        // dropped/ignored chapter flow would make this zero.
        val books = MutableStateFlow(listOf(book("a", totalDurationSeconds = 0L)))
        val progressFlow = MutableStateFlow(listOf(progress("a", positionSeconds = 120L)))
        val chapters = MutableStateFlow(listOf(chapter("a", durationSeconds = 600L)))
        // #1163: the readable-copy id really feeds the card.
        val onDevice = MutableStateFlow(setOf("a"))
        val subject = module(
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            books, progressFlow, chapters, onDevice
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { subject.books.collect {} }

        val entry = subject.books.value.single()
        assertEquals("a", entry.book.id)
        assertTrue("readable id must reach hasReadableLocalCopy", entry.hasReadableLocalCopy)
        assertEquals("progress row must reach the card", 120L, entry.progress?.currentPositionSeconds)
        assertEquals("chapter duration must prove an unknown site total", 600L, entry.totalDurationSeconds)

        // A later Room update re-projects the same seam.
        books.value = listOf(book("a", totalDurationSeconds = 0L), book("b"))
        assertEquals(listOf("a", "b"), subject.books.value.map { it.book.id })
    }

    @Test
    fun `isLoaded stays false until the first book emission, then flips once`() = runTest {
        // Never emits until the test says so: the initial StateFlow value must
        // NOT be mistaken for "the library was read" (#1066).
        val books = MutableSharedFlow<List<AudiobookEntity>>()
        val subject = module(CoroutineScope(UnconfinedTestDispatcher(testScheduler)), books)
        runCurrent()
        assertFalse("nothing was read yet", subject.isLoaded.value)

        books.emit(listOf(book("a")))
        runCurrent()
        assertTrue("the first real emission proves the read", subject.isLoaded.value)
    }

    @Test
    fun `availability marks only problem works by mergeKey`() = runTest {
        val available = book("available", mergeKey = "available|author")
        val refusedBook = book("refused", mergeKey = "refused|author", sourceUrl = "https://4read.org/refused")
        val downloaded = book("downloaded", mergeKey = "downloaded|author", isDownloaded = true)
        val verdicts = MutableStateFlow(
            mapOf("available|author" to AvailabilityVerdict(AvailabilityStatus.NOT_FOUND, observedAtMs = 42L))
        )
        val subject = module(
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            MutableStateFlow(listOf(available, refusedBook, downloaded)),
            verdicts = verdicts,
            refused = MutableStateFlow(setOf("4read"))
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { subject.availability.collect {} }

        val availability = subject.availability.value
        assertFalse("a Work whose own source is available stays clean", "available|author" in availability)
        assertEquals(AvailabilityStatus.REFUSED, availability["refused|author"]?.status)
        assertFalse("an offline download always plays", "downloaded|author" in availability)
    }
}
