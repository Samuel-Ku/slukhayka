package com.slukhayka.audiobooks.ui.snapshots

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.ui.components.BookRow
import com.slukhayka.audiobooks.ui.theme.ContentDensity
import com.slukhayka.audiobooks.ui.theme.LocalContentDensity
import com.slukhayka.audiobooks.ui.theme.PaperInkTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Plan 2026-2027 — «Компактно» is a LAYOUT preference: the row shrinks, the
 * touch target does not. This pins the compact row and checks its height
 * against the comfortable 112 dp floor a listener sees by default.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "uk-rUA-" + RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class ContentDensitySnapshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val book = AudiobookEntity(
        id = "density-book",
        title = "Трохи ненависті",
        author = "Джо Аберкромбі",
        narrator = "Рік САН4ЕЗ",
        description = "",
        coverDrawableRes = 0,
        genre = "Фентезі",
        sourceUrl = "https://4read.org/trohy-nenavysti",
    )

    @Test
    fun compact_book_row_is_shorter_but_still_a_row() {
        composeTestRule.setContent {
            PaperInkTheme(darkTheme = false) {
                CompositionLocalProvider(
                    LocalContentDensity provides ContentDensity.COMPACT
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        Column {
                            BookRow(
                                title = book.title,
                                book = book,
                                author = book.author,
                                progress = 0.34f,
                                testTag = "density_row",
                            )
                        }
                    }
                }
            }
        }
        val density = composeTestRule.density
        val row = composeTestRule.onNodeWithTag("density_row").getUnclippedBoundsInRoot()
        val heightDp = (row.bottom - row.top).value
        assertTrue(
            "compact row must be shorter than the 112 dp comfortable row, was $heightDp dp",
            heightDp < 112f,
        )
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/book_row_compact.png"
        )
    }
}
