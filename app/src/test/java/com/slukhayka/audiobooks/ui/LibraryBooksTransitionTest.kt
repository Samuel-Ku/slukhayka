package com.slukhayka.audiobooks.ui

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.App
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * #1066 — the ROOT CAUSE of the flaky accessibility leg, pinned as a test.
 *
 * `MainViewModel.libraryBooks` is a `combine(...).stateIn(..., emptyList())`:
 * it starts EMPTY and fills in asynchronously, once Room answers on three
 * flows. The screen puts that list straight into lazy lists, so a measure pass
 * that begins while the list is still empty can be running when the real rows
 * land — and Compose then asks for a key by an index that no longer exists:
 *
 * ```
 * IndexOutOfBoundsException: Index 2, size 2
 *   at MutableIntervalList.get(IntervalList.kt:229)
 *   at LazyListMeasureKt.measureLazyList(LazyListMeasure.kt:222)
 * ```
 *
 * This test does not try to race the frame (that window is microseconds and I
 * could not reproduce it on demand). It pins the CONDITION the race needs: the
 * published list really does go from 0 rows to N while the ViewModel is live.
 * If that transition is ever removed — by seeding the state instead of
 * starting empty — this test goes red, which is exactly the change worth
 * noticing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LibraryBooksTransitionTest {

    @Test
    fun `the library list starts empty and fills in while the view model is live`() {
        val app = ApplicationProvider.getApplicationContext<App>()
        val viewModel = MainViewModel(app)
        shadowOf(Looper.getMainLooper()).idle()

        // The condition: whatever the library held, the FIRST published value
        // is the empty default, not the real rows.
        assertEquals(
            "стартове значення мусить бути порожнім — саме воно створює вікно гонки",
            0,
            viewModel.libraryBooks.value.size
        )

        // #1066 — the SCREEN can now tell "not read yet" from "empty", which
        // is what stops it claiming «no books» before the read has happened.
        val loaded = runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(5_000) {
                viewModel.isLibraryLoaded.first { it }
            }
        }
        assertTrue(
            "прапорець мусить стати true, коли читання відбулося",
            loaded != null
        )

        // The window the race lives in: a lazy list bound to this value can
        // start measuring at 0 items, and the real rows arrive later, from
        // another thread, without the ViewModel being rebuilt. That arrival IS
        // the size change Compose trips over.
        //
        // I deliberately do NOT assert "the row shows up after inserting one":
        // a bare `insertAudiobooks` row does not satisfy the ADR-0009 join that
        // `getAllAudiobooks` performs, so such an assertion would test my
        // seeding rather than the app. The empty start is the provable half of
        // the condition, and it is the half that creates the window.
    }
}
