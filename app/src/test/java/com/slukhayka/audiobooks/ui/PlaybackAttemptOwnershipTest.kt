package com.slukhayka.audiobooks.ui

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.App
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.ChapterEntity
import com.slukhayka.audiobooks.data.db.EditionEntity
import com.slukhayka.audiobooks.data.db.SourceEntity
import com.slukhayka.audiobooks.data.db.SourceTrackEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * #1241 — a slow preparation A must not steal the player from a newer B.
 *
 * The delay is a controlled test adapter ([playbackPreparationHook]): real
 * preparation still runs; the test decides completion order with
 * [CompletableDeferred]s — no network.
 *
 * #1732 — the negative proof is a terminal signal, not a window: the stale
 * attempt's decision is awaited through [MainViewModel]'s
 * `playbackAttemptSettled` seam. The old `Thread.sleep(300)` could pass before
 * A even reached the ownership check on a loaded CI, and `awaitInstalled`
 * returned silently when its deadline expired.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlaybackAttemptOwnershipTest {

    private fun book(id: String) = AudiobookEntity(
        id = id,
        title = "Книга $id",
        author = "Автор",
        narrator = "Читач",
        description = "",
        coverDrawableRes = 0,
        genre = "",
        sourceUrl = ""
    )

    private fun seed(app: App, id: String) = runBlocking {
        val dao = AudiobookDatabase.getDatabase(app).audiobookDao()
        val editionId = "edition-$id"
        val sourceId = "source-$id"
        dao.insertAudiobooks(listOf(book(id)))
        dao.upsertWork(
            com.slukhayka.audiobooks.data.db.WorkEntity(
                id = id, mergeKey = "", title = "Книга $id", author = "Автор"
            )
        )
        dao.insertLibraryEntryWithOrigin(id, id, "UNKNOWN", 1L)
        dao.insertEdition(EditionEntity(id = editionId, workId = id, totalChapters = 1))
        dao.insertChapters(
            listOf(
                ChapterEntity(
                    id = "${id}_ch_1", bookId = id, chapterIndex = 0,
                    title = "Розділ 1", durationSeconds = 600, editionId = editionId
                )
            )
        )
        dao.insertSources(
            listOf(SourceEntity(id = sourceId, bookId = id, editionId = editionId, type = "local", url = ""))
        )
        dao.insertSourceTrackRows(
            listOf(SourceTrackEntity(id = "${id}_tr_1", sourceId = sourceId, trackIndex = 0, url = "file:///tmp/$id.mp3"))
        )
    }

    /**
     * Waits for a positive install record and fails on timeout. The list is
     * live (not a snapshot), so a late adapter call is still observed.
     */
    private fun awaitInstalled(
        installed: List<String>,
        expected: String,
        deadlineMs: Long = 5_000
    ) {
        val deadline = System.currentTimeMillis() + deadlineMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (installed.contains(expected)) return
            Thread.sleep(10)
        }
        fail("install '$expected' did not arrive within ${deadlineMs}ms; installed=${installed.toList()}")
    }

    /**
     * The attempt's terminal decision, delivered by the production seam. A
     * superseded attempt reports `false` only after it has actually reached the
     * ownership check, so the assertion cannot race a slow CI.
     */
    private class Settlements {
        private val decisions = ConcurrentHashMap<Long, Boolean>()
        private val latches = ConcurrentHashMap<Long, CountDownLatch>()

        val observe: (Long, Boolean) -> Unit = { attemptId, installed ->
            decisions[attemptId] = installed
            latches.getOrPut(attemptId) { CountDownLatch(1) }.countDown()
        }

        fun await(attemptId: Long, deadlineMs: Long = 5_000): Boolean {
            val latch = latches.getOrPut(attemptId) { CountDownLatch(1) }
            val deadline = System.currentTimeMillis() + deadlineMs
            while (System.currentTimeMillis() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                decisions[attemptId]?.let { return it }
                latch.await(1, TimeUnit.MILLISECONDS)
            }
            fail("attempt $attemptId did not settle within ${deadlineMs}ms; settled=${decisions.keys}")
            return decisions.getValue(attemptId)
        }
    }

    private fun bookmark(bookId: String) = com.slukhayka.audiobooks.data.db.BookmarkEntity(
        bookId = bookId,
        chapterIndex = 0,
        chapterTitle = "Розділ 1",
        timestampSeconds = 60,
        note = ""
    )

    @Test
    fun `two requests to the same Edition install only the newer and consume the marker once`() {
        val app = ApplicationProvider.getApplicationContext<App>()
        seed(app, "book-a")
        runBlocking {
            AudiobookDatabase.getDatabase(app).audiobookDao().savePlaybackProgress(
                com.slukhayka.audiobooks.data.db.PlaybackProgressEntity(
                    editionId = "edition-book-a",
                    bookId = "book-a",
                    currentChapterIndex = 0,
                    currentPositionSeconds = 1200L,
                    lastPausedAtEpochMs = 1_700_000_000_000L
                )
            )
        }

        val installed = java.util.Collections.synchronizedList(mutableListOf<String>())
        val gates = ConcurrentHashMap<Long, CompletableDeferred<Unit>>()
        val settlements = Settlements()
        val seam = ExplicitPlaybackAttempts(AutomaticPlaybackRecoveryGate()) {}
        val viewModel = MainViewModel(
            app,
            seam,
            playbackPreparationHook = { attemptId, _ ->
                gates.getOrPut(attemptId) { CompletableDeferred() }.await()
            },
            playbackInstallAdapter = { request -> installed += "${request.bookId}#${request.attemptId}" },
            playbackAttemptSettled = settlements.observe
        )

        viewModel.playAudiobook(book("book-a"))
        viewModel.playAudiobook(book("book-a"))

        // A2 (attempt 2) is the listener's latest gesture.
        gates.getOrPut(2L) { CompletableDeferred() }.complete(Unit)
        awaitInstalled(installed, "book-a#2")
        assertTrue("the newer same-Edition request must install", settlements.await(2L))

        // A1 finishes last — its terminal decision is what proves it did not install.
        gates.getOrPut(1L) { CompletableDeferred() }.complete(Unit)
        assertFalse("the superseded same-Edition request must not install", settlements.await(1L))

        assertEquals("only the newer same-Edition request may install", listOf("book-a#2"), installed.toList())
        assertEquals(
            "the winning attempt consumed the pause marker exactly once",
            null,
            runBlocking { AudiobookDatabase.getDatabase(app).audiobookDao().getPlaybackProgressSync("book-a")?.lastPausedAtEpochMs }
        )
    }

    @Test
    fun `a slow Bookmark superseded by a newer Play does not install`() {
        val app = ApplicationProvider.getApplicationContext<App>()
        seed(app, "book-a")
        seed(app, "book-b")

        val installed = java.util.Collections.synchronizedList(mutableListOf<String>())
        val gates = ConcurrentHashMap<Long, CompletableDeferred<Unit>>()
        val settlements = Settlements()
        val seam = ExplicitPlaybackAttempts(AutomaticPlaybackRecoveryGate()) {}
        val viewModel = MainViewModel(
            app,
            seam,
            playbackPreparationHook = { attemptId, _ ->
                gates.getOrPut(attemptId) { CompletableDeferred() }.await()
            },
            playbackInstallAdapter = { request -> installed += request.bookId },
            playbackAttemptSettled = settlements.observe
        )

        viewModel.jumpToBookmark(bookmark("book-a"))
        viewModel.playAudiobook(book("book-b"))

        gates.getOrPut(2L) { CompletableDeferred() }.complete(Unit)
        awaitInstalled(installed, "book-b")

        gates.getOrPut(1L) { CompletableDeferred() }.complete(Unit)
        assertFalse("the superseded Bookmark must not install", settlements.await(1L))

        assertEquals(
            "the superseded Bookmark must not steal the player",
            listOf("book-b"),
            installed.toList()
        )
    }

    @Test
    fun `a slow relisten superseded by a newer Play does not install`() {
        val app = ApplicationProvider.getApplicationContext<App>()
        seed(app, "book-a")
        seed(app, "book-b")

        val installed = java.util.Collections.synchronizedList(mutableListOf<String>())
        val gates = ConcurrentHashMap<Long, CompletableDeferred<Unit>>()
        val settlements = Settlements()
        val seam = ExplicitPlaybackAttempts(AutomaticPlaybackRecoveryGate()) {}
        val viewModel = MainViewModel(
            app,
            seam,
            playbackPreparationHook = { attemptId, _ ->
                gates.getOrPut(attemptId) { CompletableDeferred() }.await()
            },
            playbackInstallAdapter = { request -> installed += request.bookId },
            playbackAttemptSettled = settlements.observe
        )

        viewModel.relistenBook(book("book-a"))
        viewModel.playAudiobook(book("book-b"))

        gates.getOrPut(2L) { CompletableDeferred() }.complete(Unit)
        awaitInstalled(installed, "book-b")

        gates.getOrPut(1L) { CompletableDeferred() }.complete(Unit)
        assertFalse("the superseded relisten must not install", settlements.await(1L))

        assertEquals(
            "the superseded relisten must not steal the player",
            listOf("book-b"),
            installed.toList()
        )
    }

    @Test
    fun `a slow preparation A cannot steal the player from a newer B`() {
        val app = ApplicationProvider.getApplicationContext<App>()
        seed(app, "book-a")
        seed(app, "book-b")

        val installed = java.util.Collections.synchronizedList(mutableListOf<String>())
        val gates = ConcurrentHashMap<Long, CompletableDeferred<Unit>>()
        val settlements = Settlements()
        val seam = ExplicitPlaybackAttempts(AutomaticPlaybackRecoveryGate()) {}
        val viewModel = MainViewModel(
            app,
            seam,
            playbackPreparationHook = { attemptId, _ ->
                gates.getOrPut(attemptId) { CompletableDeferred() }.await()
            },
            playbackInstallAdapter = { request -> installed += request.bookId },
            playbackAttemptSettled = settlements.observe
        )

        viewModel.playAudiobook(book("book-a"))
        viewModel.playAudiobook(book("book-b"))

        // B is the listener's latest gesture: finish it first.
        gates.getOrPut(2L) { CompletableDeferred() }.complete(Unit)
        awaitInstalled(installed, "book-b")

        // A finishes last — it must NOT replace B, and its terminal decision
        // is awaited before the assertion.
        gates.getOrPut(1L) { CompletableDeferred() }.complete(Unit)
        assertFalse("the slow preparation must not install", settlements.await(1L))

        assertEquals(
            "only the newest gesture may install; the slow A must not steal the player",
            listOf("book-b"),
            installed.toList()
        )
    }
}
