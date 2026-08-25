package com.slukhayka.audiobooks.ui

import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import com.slukhayka.audiobooks.data.catalog.SourceCatalog
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.ui.screens.BookDetailIdentityHeader
import com.slukhayka.audiobooks.ui.screens.BookDetailPrimaryActions
import com.slukhayka.audiobooks.ui.screens.FavoriteButton
import com.slukhayka.audiobooks.ui.screens.WorkSourceRowCard
import com.slukhayka.audiobooks.ui.screens.bookDetailPresentation
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BookDetailAccessibilityTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val book = AudiobookEntity(
        id = "book-detail-a11y",
        title = "Трохи ненависті",
        author = "Джо Аберкромбі",
        narrator = "Pik CAH4E3",
        description = "Опис",
        coverDrawableRes = 0,
        coverImageUrl = null,
        genre = "Фентезі",
        sourceUrl = "https://4read.org/trohy-nenavysti",
        totalChapters = 4,
        totalDurationSeconds = 3_600
    )

    @Test
    fun identityHeaderReadsWorkTitleOnceAndMarksItAsHeading() {
        val presentation = bookDetailPresentation(book, emptyList(), emptyList())

        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface {
                    BookDetailIdentityHeader(book = book, presentation = presentation)
                }
            }
        }

        composeTestRule.onAllNodesWithText(book.title, substring = false)
            .assertCountEquals(1)
        composeTestRule.onNodeWithText(book.title)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
        composeTestRule.onNodeWithContentDescription(book.title, useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun favoriteActionExposesCurrentStateAndContextualNextAction() {
        var clicked = false
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                FavoriteButton(
                    isFavorite = false,
                    bookTitle = book.title,
                    onToggle = { clicked = true }
                )
            }
        }

        composeTestRule.onNodeWithTag("favorite_toggle_button")
            .assertContentDescriptionEquals("Додати «${book.title}» до улюблених")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Не в улюблених"
                )
            )
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
            .performClick()
        assertTrue(clicked)
    }

    @Test
    fun currentSourceExposesSelectionAndAlternativeSourceExposesContextualAction() {
        val presentation = bookDetailPresentation(
            book = book,
            sourceProfiles = emptyList(),
            playableSources = listOf(
                SourceCatalog.WorkSourceRow(
                    sourceId = "4read",
                    sourceName = "4read",
                    url = book.sourceUrl,
                    streamOnly = false
                ),
                SourceCatalog.WorkSourceRow(
                    sourceId = "sluhay",
                    sourceName = "Sluhay",
                    url = "https://sluhay.example/book",
                    streamOnly = true
                )
            )
        )
        var selected = false

        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface {
                    Column {
                        WorkSourceRowCard(
                            source = presentation.sources[0],
                            workTitle = book.title,
                            onClick = {}
                        )
                        WorkSourceRowCard(
                            source = presentation.sources[1],
                            workTitle = book.title,
                            onClick = { selected = true }
                        )
                    }
                }
            }
        }

        composeTestRule.onNodeWithTag("work_source_4read")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.Selected,
                    true
                )
            )
        composeTestRule.onNodeWithTag("work_source_sluhay")
            .assertContentDescriptionEquals("Відтворити «${book.title}» із джерела Sluhay")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Інше джерело, тільки стрімінг"
                )
            )
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertTrue(selected)
    }

    @Test
    fun primaryActionsRemainReachableAtTwoHundredPercentFontScale() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 2f)) {
                    Surface {
                        BookDetailPrimaryActions(
                            workTitle = book.title,
                            playLabel = "Слухати",
                            streamOnly = false,
                            isDownloaded = false,
                            isDownloading = false,
                            downloadProgress = 0f,
                            onPlay = {},
                            onDownload = {},
                            onAddBookmark = {},
                            modifier = Modifier.width(320.dp)
                        )
                    }
                }
            }
        }

        listOf("play_book_button", "download_offline_button", "bookmark_button").forEach { tag ->
            composeTestRule.onNodeWithTag(tag)
                .assertExists()
                .assertHeightIsAtLeast(48.dp)
        }
    }
}
