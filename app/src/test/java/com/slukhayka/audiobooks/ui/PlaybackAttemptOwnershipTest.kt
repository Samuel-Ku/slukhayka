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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentHashMap

/**
 * #1241 — a slow preparation A must not steal the player from a newer B.
 *
 * The delay is a controlled test adapter ([playbackPreparationHook]): real
 * preparation still runs; the test decides completion order with
 * [CompletableDeferred]s — no sleep, no network. Without the ownership guard the
 * finishing A installs itself and this test goes red, which is the negative
 * case the ticket asks to reproduce first.
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

    private fun awaitInstalled(installed: List<String>, expected: String, deadlineMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + deadlineMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (installed.contains(expected)) return
            Thread.sleep(10)
        }
    }

    @Test
    fun `a slow preparation A cannot steal the player from a newer B`() {
        val app = ApplicationProvider.getApplicationContext<App>()
        seed(app, "book-a")
        seed(app, "book-b")

        val installed = java.util.Collections.synchronizedList(mutableListOf<String>())
        val gates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        val seam = ExplicitPlaybackAttempts(AutomaticPlaybackRecoveryGate()) {}
        val viewModel = MainViewModel(
            app,
            seam,
            playbackPreparationHook = { bookId ->
                gates.getOrPut(bookId) { CompletableDeferred() }.await()
            },
            playbackInstallAdapter = { request -> installed += request.bookId }
        )

        viewModel.playAudiobook(book("book-a"))
        viewModel.playAudiobook(book("book-b"))

        // B is the listener's latest gesture: finish it first.
        gates.getOrPut("book-b") { CompletableDeferred() }.complete(Unit)
        awaitInstalled(installed, "book-b")

        // A finishes last — it must NOT replace B.
        gates.getOrPut("book-a") { CompletableDeferred() }.complete(Unit)
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(300)

        assertEquals(
            "only the newest gesture may install; the slow A must not steal the player",
            listOf("book-b"),
            installed.toList()
        )
    }
}
