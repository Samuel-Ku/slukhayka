package com.slukhayka.audiobooks.ui.snapshots

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.foundation.layout.width
import com.slukhayka.audiobooks.ui.screens.bookdetail.SeriesPill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import com.slukhayka.audiobooks.data.catalog.SourceCatalog
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.entries.LibraryEntries
import com.slukhayka.audiobooks.ui.screens.bookdetail.BookDetailCanonicalSummary
import com.slukhayka.audiobooks.ui.screens.bookdetail.BookDetailDescription
import com.slukhayka.audiobooks.ui.screens.BookDetailPresentation
import com.slukhayka.audiobooks.ui.screens.bookdetail.BookDetailSourceSection
import com.slukhayka.audiobooks.ui.screens.bookDetailPresentation
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The complete canonical metadata region for spec-41, from the screen model. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "uk-rUA-" + RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class BookDetailMetadataSnapshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val book = AudiobookEntity(
        id = "book",
        title = "Трохи ненависті",
        author = "Джо Аберкромбі",
        narrator = "Pik CAH4E3",
        description = "Аудіокнига з каталогу 4read.org",
        coverDrawableRes = 0,
        genre = "Пригоди · Фентезі",
        sourceUrl = "https://4read.org/trohy-nenavysti",
        isDownloaded = false,
        rating = 4.6f
    )
    private val realDescription =
        "Над Адуа зависочіли промислові труби, а нова ера змінює знайомий світ."
    private val profile = LibraryEntries.SourceProfile(
        sourceId = "4read",
        sourceName = "4read",
        url = book.sourceUrl,
        description = realDescription,
        rating = 4.6,
        narrator = book.narrator,
        genres = listOf("Пригоди", "Фентезі")
    )
    private val source = SourceCatalog.WorkSourceRow(
        sourceId = "4read",
        sourceName = "4read",
        url = book.sourceUrl,
        streamOnly = false
    )

    @Test
    fun series_navigation_centers_its_label_and_wraps_long_titles() {
        var opened = 0
        val title = "Епоха божевілля"
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Column {
                    SeriesPill(title, 1, onClick = { opened++ })
                    SeriesPill("Дуже довга назва циклу про повернення додому", 12,
                        modifier = Modifier.width(160.dp), onClick = {})
                }
            }
        }
        val label = composeTestRule.onNodeWithText("«$title» • Кн. 1", useUnmergedTree = true)
        val labelBounds = label.fetchSemanticsNode().boundsInRoot
        val control = composeTestRule.onAllNodesWithTag("book_detail_series_pill")[0].fetchSemanticsNode().boundsInRoot
        val shortLayouts = mutableListOf<TextLayoutResult>()
        label.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(shortLayouts) }
        val shortLayout = shortLayouts.single()
        val textCenter = labelBounds.top + (shortLayout.getLineTop(0) + shortLayout.getLineBottom(0)) / 2
        assertEquals("label should be vertically centered", control.center.y, textCenter, 1f)
        label.performClick()
        assertEquals(1, opened)
        val layouts = mutableListOf<TextLayoutResult>()
        composeTestRule.onNodeWithText("«Дуже довга назва циклу про повернення додому» • Кн. 12", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("long cycle name should wrap", layouts.single().lineCount > 1)
        assertTrue("cycle name should remain readable", !layouts.single().hasVisualOverflow)
    }

    @Test
    fun single_source_metadata_region() {
        val presentation = bookDetailPresentation(book, listOf(profile), listOf(source))
        setMetadataRegion(presentation)

        composeTestRule.onAllNodesWithText("4read", substring = false).assertCountEquals(1)
        composeTestRule.onAllNodesWithText(book.narrator, substring = true).assertCountEquals(1)
        composeTestRule.onAllNodesWithText(book.genre, substring = false).assertCountEquals(1)
        composeTestRule.onNodeWithText(realDescription).assertExists()
        composeTestRule.onAllNodesWithText("Що кажуть джерела").assertCountEquals(0)
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/book_detail_metadata_single_source.png"
        )
    }

    @Test
    fun multiple_source_metadata_region() {
        val otherSource = SourceCatalog.WorkSourceRow(
            sourceId = "sluhay",
            sourceName = "Sluhay",
            url = "https://sluhay.example/trohy-nenavysti",
            streamOnly = true
        )
        val otherProfile = profile.copy(
            sourceId = otherSource.sourceId,
            sourceName = otherSource.sourceName,
            url = otherSource.url,
            rating = 4.8,
            description = "Інший короткий опис."
        )
        val presentation = bookDetailPresentation(
            book,
            listOf(profile, otherProfile),
            listOf(source, otherSource)
        )
        setMetadataRegion(presentation)

        composeTestRule.onNodeWithText("Джерела").assertExists()
        composeTestRule.onNodeWithText("Поточна").assertExists()
        composeTestRule.onAllNodesWithText(book.narrator, substring = true).assertCountEquals(1)
        composeTestRule.onAllNodesWithText(book.genre, substring = false).assertCountEquals(1)
        composeTestRule.onNodeWithText("Інший опис від джерела:", substring = true).assertExists()
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/book_detail_metadata_multiple_sources.png"
        )
    }
    private fun setMetadataRegion(presentation: BookDetailPresentation) {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        BookDetailCanonicalSummary(presentation)
                        BookDetailDescription(presentation)
                        BookDetailSourceSection(presentation)
                    }
                }
            }
        }
    }
}
