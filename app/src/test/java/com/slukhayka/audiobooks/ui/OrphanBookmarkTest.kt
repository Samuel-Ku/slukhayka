package com.slukhayka.audiobooks.ui

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.App
import com.slukhayka.audiobooks.data.db.BookmarkEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * #1081 — a bookmark can outlive its book.
 *
 * The report: «сьогодні я слухаю книгу… поставив закладку. На наступний день
 * вона не запускається по закладці», clarified to «тап по закладці **не робить
 * нічого взагалі** — плеєр не відкривається, повідомлення немає, застосунок не
 * падає».
 *
 * That silence was the bug. `jumpToBookmark` did
 * `getBookSync(bookmark.bookId) ?: return@launch`, so a bookmark whose row was
 * merged away, re-imported under a new id or repaired (#470) led nowhere and
 * said nothing.
 *
 * The bookmarks list has no join, so a dead entry still LOOKS alive — which is
 * why the listener kept tapping it. The fix does not resurrect the book; it
 * makes the app admit the bookmark is stale.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OrphanBookmarkTest {

    private fun orphanBookmark() = BookmarkEntity(
        bookId = "no-such-book",
        chapterIndex = 0,
        chapterTitle = "Розділ 1",
        timestampSeconds = 60,
        note = ""
    )

    /**
     * Waits for the message instead of assuming it has arrived.
     *
     * `jumpToBookmark` launches on `Dispatchers.IO`, and
     * `shadowOf(Looper.getMainLooper()).idle()` drains the MAIN looper — it
     * does not wait for IO. The first version of this test idled once and
     * asserted, which passed locally and FAILED in CI: a race, not a fix.
     * CI caught it, and this is the correction.
     */
    private fun awaitBookmarkMessage(viewModel: MainViewModel): String? {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            viewModel.bookmarkMessage.value?.let { return it }
            Thread.sleep(20)
        }
        return viewModel.bookmarkMessage.value
    }

    @Test
    fun `a bookmark whose book is gone SAYS SO instead of doing nothing`() {
        val app = ApplicationProvider.getApplicationContext<App>()
        val viewModel = MainViewModel(app)

        // The app's database is empty in this test, so the book cannot resolve —
        // exactly the state the listener was in.
        viewModel.jumpToBookmark(orphanBookmark())

        assertNotNull(
            "the tap must produce a message — silence is what the report called broken",
            awaitBookmarkMessage(viewModel)
        )
    }

    @Test
    fun `an orphan bookmark does NOT open the player`() {
        // The other half of honesty: a message, not a player showing an empty
        // book. Opening the full player here would claim there is something to
        // play.
        val app = ApplicationProvider.getApplicationContext<App>()
        val viewModel = MainViewModel(app)

        viewModel.jumpToBookmark(orphanBookmark())
        awaitBookmarkMessage(viewModel)

        assertFalse(
            "there is no book to play, so the player must not open",
            viewModel.showFullPlayer.value
        )
    }

    @Test
    fun `the notice is one-shot and can be consumed`() {
        val app = ApplicationProvider.getApplicationContext<App>()
        val viewModel = MainViewModel(app)

        viewModel.jumpToBookmark(orphanBookmark())
        assertNotNull(awaitBookmarkMessage(viewModel))

        viewModel.consumeBookmarkMessage()
        assertFalse(
            "the message must clear, or the snackbar reappears on every recomposition",
            viewModel.bookmarkMessage.value != null
        )
    }

    /**
     * #1081 — the anchor's chapter can stop existing.
     *
     * A bookmark records `(bookId, chapterIndex, time)`, but a Chapter list is
     * not immutable: a short `/play` answer cuts it (cf. the closed #1035 and
     * #1037). `loadAndPlayBook` merely clamps an out-of-range index —
     * `coerceIn(0, size-1)` — so the listener landed at the very end of the
     * LAST chapter and the audio never started: the same "tap does nothing"
     * symptom, by a different route.
     *
     * The book here has no playable chapters at all, which is the degenerate
     * case of the same condition (every index is out of range). The tap must
     * still not be silent, and the notice must be the CHAPTER one — the book
     * resolves fine, so blaming the library would be a lie.
     */
    @Test
    fun `a bookmark whose chapter no longer exists says so`() {
        val app = ApplicationProvider.getApplicationContext<App>()
        val viewModel = MainViewModel(app)

        // A REAL book row, so `getBookSync` resolves and the chapter check is
        // the one under test (the orphan path is covered above).
        runBlocking {
            com.slukhayka.audiobooks.data.db.AudiobookDatabase.getDatabase(app)
                .audiobookDao()
                .insertAudiobooks(
                listOf(
                    com.slukhayka.audiobooks.data.db.AudiobookEntity(
                        id = "book-without-chapters",
                        title = "Книга без розділів",
                        author = "Автор",
                        narrator = "",
                        description = "",
                        coverDrawableRes = 0,
                        genre = "",
                        sourceUrl = "https://sound-books.net/x"
                    )
                )
            )
        }

        viewModel.jumpToBookmark(
            BookmarkEntity(
                bookId = "book-without-chapters",
                chapterIndex = 7,
                chapterTitle = "Розділ 8",
                timestampSeconds = 60,
                note = ""
            )
        )

        val message = awaitBookmarkMessage(viewModel)
        assertNotNull("тап не має бути тихим і тут", message)
        val expected = app.getString(com.slukhayka.audiobooks.R.string.bookmark_chapter_gone)
        assertEquals(
            "книга резолвиться, тож повідомлення має бути про розділ, а не про бібліотеку",
            expected,
            message
        )
    }
}
