package com.slukhayka.audiobooks.ui.library

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.AppLocale
import com.slukhayka.audiobooks.AppLocalePrefs
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.testing.TestDataFactory
import com.slukhayka.audiobooks.ui.library.ListenComposer.BlockId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ListenShelvesModuleTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val books = MutableSharedFlow<List<LibraryBook>>(replay = 1)
    private val series = MutableStateFlow<AudiobookEntity?>(null)
    private val finished = MutableStateFlow(emptySet<String>())
    private val now = TestDataFactory.FIXED_CLOCK_MS + 30L * 24 * 3600 * 1000

    private fun book(downloaded: Boolean = true, duration: Long = 3600L) =
        TestDataFactory.dataBooks().first().copy(isDownloaded = downloaded, totalDurationSeconds = duration).apply {
            isFavorite = false
            createdAt = TestDataFactory.FIXED_CLOCK_MS
        }

    private fun TestScope.listen(): ListenShelvesModule {
        val shelves = ListenShelvesModule(
            scope = backgroundScope,
            composedBooks = books,
            nextInSeries = series,
            finishedWorkIds = finished,
            prefs = ListenPrefsStore(app),
            copyForApp = { ResourceListenCopy.forApp(app) },
            now = { now },
        )
        backgroundScope.launch { shelves.state.collect {} }
        runCurrent()
        return shelves
    }

    private fun TestScope.drain() {
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
    }

    @Test
    fun `only the first real empty library can claim fresh install, even with a stale next volume`() = runTest {
        series.value = book()
        val shelves = listen()
        assertEquals(ListenContent.Loading, shelves.state.value.content)
        assertTrue(shelves.state.value.displayBlocks.isEmpty())

        books.emit(emptyList())
        runCurrent()
        assertEquals(ListenContent.FreshInstall, shelves.state.value.content)
    }

    @Test
    fun `hiding and moving overlapping shelves changes which one displays the work`() = runTest {
        val book = book()
        books.emit(buildLibraryBooks(listOf(book), emptyList(), emptyMap()))
        val shelves = listen()
        val travel = BlockId.TRAVEL
        val short = BlockId.SHORT

        fun assertShelves(managed: List<BlockId>, hidden: Set<BlockId>, vararg displayed: BlockId) {
            drain()
            fun contents(blocks: List<ListenComposer.Block>) =
                blocks.map { block -> block.id to block.books.map { it.book.id } }
            val state = shelves.state.value
            assertEquals(managed.map { it to listOf(book.id) }, contents(state.managementBlocks))
            assertEquals(displayed.map { it to listOf(book.id) }, contents(state.displayBlocks))
            assertEquals(hidden, state.hiddenBlockIds)
            assertEquals(if (displayed.isEmpty()) ListenContent.AllShelvesHidden else ListenContent.Shelves, state.content)
        }

        val travelFirst = listOf(travel, short)
        val shortFirst = listOf(short, travel)
        assertShelves(travelFirst, emptySet(), travel)
        shelves.hideBlock(travel)
        assertShelves(travelFirst, setOf(travel), short)
        shelves.unhideBlock(travel)
        assertShelves(travelFirst, emptySet(), travel)
        shelves.moveBlockUp(short)
        assertShelves(shortFirst, emptySet(), short)
        shelves.hideBlock(short)
        assertShelves(shortFirst, setOf(short), travel)
        shelves.hideBlock(travel)
        assertShelves(shortFirst, setOf(short, travel))
        shelves.restoreHiddenBlocks()
        assertShelves(shortFirst, emptySet(), short)

        shelves.dismissBook(book.id)
        assertShelves(emptyList(), emptySet())
        shelves.undismissBook(book.id)
        assertShelves(shortFirst, emptySet(), short)
    }

    @Test
    fun `a visible hero claims its work even below travel and a hidden hero releases it`() = runTest {
        val book = book()
        val progress = TestDataFactory.seedPlaybackProgress(listOf(book)).single().copy(lastListenedAt = now)
        books.emit(buildLibraryBooks(listOf(book), listOf(progress), emptyMap()))
        val shelves = listen()

        repeat(4) { shelves.moveBlockDown(BlockId.HERO) }
        drain()
        val reordered = shelves.state.value
        assertEquals(listOf(BlockId.TRAVEL, BlockId.HERO, BlockId.SHORT), reordered.managementBlocks.map { it.id })
        assertEquals(listOf(BlockId.HERO), reordered.displayBlocks.map { it.id })

        shelves.hideBlock(BlockId.HERO)
        drain()
        val hidden = shelves.state.value
        assertEquals(book.id, hidden.heroBook?.id)
        assertEquals(listOf(BlockId.TRAVEL), hidden.displayBlocks.map { it.id })
        assertEquals(book.id, hidden.displayBlocks.single().books.single().book.id)
    }

    @Test
    fun `a finished readthrough alone adds a shelf to an otherwise ineligible library`() = runTest {
        val book = book(downloaded = false, duration = 24 * 3600L)
        books.emit(buildLibraryBooks(listOf(book), emptyList(), emptyMap()))
        val shelves = listen()
        ListenPrefsStore(app).hideBlock(BlockId.TRAVEL)
        drain()
        assertEquals(ListenContent.AllShelvesHidden, shelves.state.value.content)
        assertEquals(setOf(BlockId.TRAVEL), shelves.state.value.hiddenBlockIds)
        assertTrue(shelves.state.value.managementBlocks.isEmpty())

        finished.value = setOf(book.id)
        runCurrent()
        assertEquals(ListenContent.Shelves, shelves.state.value.content)
        val row = shelves.state.value.displayBlocks.single()
        assertEquals(BlockId.FINISHED, row.id)
        assertEquals(book.id, row.books.single().book.id)
    }

    @Test
    fun `changing only the stored UI locale replaces the next volume title reason and part together`() = runTest {
        val locale = AppLocalePrefs(app)
        locale.locale = AppLocale.UKRAINIAN
        val book = book()
        series.value = book.copy(id = "next").apply {
            seriesTitle = "Saga"
            seriesIndex = 2
        }
        books.emit(buildLibraryBooks(listOf(book), emptyList(), emptyMap()))
        val shelves = listen()

        fun words(state: ListenShelvesState): List<String?> {
            val next = state.displayBlocks.single { it.id == BlockId.NEXT_IN_SERIES }
            return listOf(next.title, next.reason, next.partCaption)
        }
        val first = shelves.state.value
        val ukrainian = listOf("Продовжити серію", "Наступний том: Saga", "Частина 2")
        assertEquals(ukrainian, words(first))
        locale.locale = AppLocale.ENGLISH
        shelves.onUiLocaleApplied()
        drain()
        assertEquals(listOf("Continue the series", "Next volume: Saga", "Part 2"), words(shelves.state.value))
        assertEquals(ukrainian, words(first))
    }
}
