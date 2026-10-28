package com.slukhayka.audiobooks.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.ui.screens.rememberSeriesBooks
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1506: «У серії» must belong to the open book. Before the fix the state was
 * `remember { }` without a book key, and a book without `seriesUrl` returned
 * early from the effect without clearing it — so A(series) → B(no series) kept
 * showing A's volumes, and tapping them opened a foreign book.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BookSeriesRowStateTest {

    @get:Rule
    val compose = createComposeRule()

    private val currentBook = mutableStateOf(book("a", seriesUrl = "series-1"))

    private fun book(id: String, seriesUrl: String? = null) = AudiobookEntity(
        id = id,
        title = "Книга $id",
        author = "Автор",
        narrator = "",
        description = "",
        coverDrawableRes = 0,
        genre = "",
        sourceUrl = "https://example.invalid/$id"
    ).apply { this.seriesUrl = seriesUrl }

    private fun setContent(load: suspend (String) -> List<AudiobookEntity>) {
        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                val books = rememberSeriesBooks(
                    currentBookId = currentBook.value.id,
                    seriesUrl = currentBook.value.seriesUrl,
                    load = load
                )
                Column {
                    Text("count:${books.size}")
                    books.forEach { Text("book:${it.id}") }
                }
            }
        }
    }

    @Test
    fun aBookWithoutSeriesDropsThePreviousVolumesInTheFirstFrame() {
        setContent { url ->
            when (url) {
                "series-1" -> listOf(book("a-1"), book("a-2"))
                else -> emptyList()
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("count:2").fetchSemanticsNodes().isNotEmpty()
        }

        currentBook.value = book("b")
        compose.waitForIdle()

        compose.onNodeWithText("count:0").assertIsDisplayed()
        compose.onNodeWithText("book:a-1").assertDoesNotExist()
        compose.onNodeWithText("book:a-2").assertDoesNotExist()
    }

    @Test
    fun aSwitchToAnotherSeriesShowsNoOldVolumesBeforeTheNewFetchFinishes() {
        val secondFetch = CompletableDeferred<List<AudiobookEntity>>()
        setContent { url ->
            when (url) {
                "series-1" -> listOf(book("a-1"), book("a-2"))
                "series-2" -> secondFetch.await()
                else -> emptyList()
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("count:2").fetchSemanticsNodes().isNotEmpty()
        }

        currentBook.value = book("c", seriesUrl = "series-2")
        compose.waitForIdle()

        // The new fetch is still suspended: no old volumes may be on screen.
        compose.onNodeWithText("count:0").assertIsDisplayed()
        compose.onNodeWithText("book:a-1").assertDoesNotExist()

        secondFetch.complete(listOf(book("c-1")))
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("book:c-1").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("count:1").assertIsDisplayed()
        compose.onNodeWithText("book:a-1").assertDoesNotExist()
    }

    @Test
    fun theSeriesRowStillExcludesTheOpenedBookItself() {
        setContent { _ -> listOf(book("a"), book("a-1")) }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("book:a-1").fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithText("count:1").assertIsDisplayed()
        compose.onNodeWithText("book:a").assertDoesNotExist()
    }
}
