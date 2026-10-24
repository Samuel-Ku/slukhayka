package com.slukhayka.audiobooks.ui

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.App
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.BookmarkEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * #1240 — the ViewModel wiring, not just the module: every explicit playback
 * gesture must pass the shared start seam BEFORE any asynchronous preparation.
 * A revert to the old hand-assembled clear/arm in `MainViewModel` leaves the
 * module tests green, so this test pins the call sites.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlaybackStartSeamWiringTest {

    private fun bookmark(bookId: String) = BookmarkEntity(
        bookId = bookId,
        chapterIndex = 0,
        chapterTitle = "Розділ 1",
        timestampSeconds = 60,
        note = ""
    )

    @Test
    fun `a bookmark clears the previous failure at gesture time, even before the book resolves`() {
        val app = ApplicationProvider.getApplicationContext<App>()
        val gate = AutomaticPlaybackRecoveryGate()
        var cleared = 0
        val seam = ExplicitPlaybackAttempts(gate) { cleared++ }
        val viewModel = MainViewModel(app, seam)

        // The book does not exist in this test DB, so the old code returned
        // before any clear/arm — the seam must have run already.
        viewModel.jumpToBookmark(bookmark("no-such-book"))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("the gesture's start seam runs synchronously", 1, cleared)
    }

    private fun book(id: String) = AudiobookEntity(
        id = id,
        title = "Книга",
        author = "Автор",
        narrator = "Читач",
        description = "",
        coverDrawableRes = 0,
        sourceUrl = "",
        genre = ""
    )

    @Test
    fun `play and relisten pass the same start seam`() {
        val app = ApplicationProvider.getApplicationContext<App>()
        val gate = AutomaticPlaybackRecoveryGate()
        var cleared = 0
        val seam = ExplicitPlaybackAttempts(gate) { cleared++ }
        val viewModel = MainViewModel(app, seam)

        viewModel.playAudiobook(book("gone-play"))
        assertEquals("Play passes the seam synchronously", 1, cleared)
        viewModel.relistenBook(book("gone-relisten"))
        assertEquals("relisten passes the same seam", 2, cleared)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `every bookmark tap starts its own attempt`() {
        val app = ApplicationProvider.getApplicationContext<App>()
        val gate = AutomaticPlaybackRecoveryGate()
        var cleared = 0
        val seam = ExplicitPlaybackAttempts(gate) { cleared++ }
        val viewModel = MainViewModel(app, seam)

        viewModel.jumpToBookmark(bookmark("gone-1"))
        viewModel.jumpToBookmark(bookmark("gone-2"))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(2, cleared)
    }
}
