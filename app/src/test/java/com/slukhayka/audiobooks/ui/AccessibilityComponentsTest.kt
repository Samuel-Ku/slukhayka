package com.slukhayka.audiobooks.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.platform.testTag
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.ui.components.AppSectionHeader
import com.slukhayka.audiobooks.ui.components.BookCoverImage
import com.slukhayka.audiobooks.ui.components.BookCoverSemantics
import com.slukhayka.audiobooks.ui.components.accessibilityModalBackground
import com.slukhayka.audiobooks.ui.components.accessibilityPane
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AccessibilityComponentsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val book = AudiobookEntity(
        id = "accessibility-cover",
        title = "Тестова книга",
        author = "Тестовий автор",
        narrator = "Тестовий виконавець",
        description = "",
        coverDrawableRes = 0,
        coverImageUrl = null,
        genre = "Фантастика",
        sourceUrl = "https://example.invalid/test"
    )

    @Test
    fun sectionHeaderIsExposedAsAHeading() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                AppSectionHeader(title = "Нещодавно слухали")
            }
        }

        composeTestRule.onNodeWithText("НЕЩОДАВНО СЛУХАЛИ")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
    }

    @Test
    fun decorativeFallbackCoverIsSilent() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                BookCoverImage(
                    book = book,
                    semantics = BookCoverSemantics.Decorative
                )
            }
        }

        composeTestRule.onNodeWithText(book.title)
            .assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription(book.title, useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun meaningfulFallbackCoverHasExactlyTheProvidedDescription() {
        val description = "Обкладинка: Тестова книга"
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                BookCoverImage(
                    book = book,
                    semantics = BookCoverSemantics.Meaningful(description)
                )
            }
        }

        composeTestRule.onNodeWithContentDescription(description)
            .assertContentDescriptionEquals(description)
        composeTestRule.onNodeWithText(book.title)
            .assertDoesNotExist()
    }

    @Suppress("DEPRECATION")
    @Test
    fun legacyNullDescriptionKeepsTheWorkTitleDuringExpandStage() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                BookCoverImage(
                    book = book,
                    contentDescription = null
                )
            }
        }

        composeTestRule.onNodeWithContentDescription(book.title)
            .assertContentDescriptionEquals(book.title)
        composeTestRule.onNodeWithText(book.title)
            .assertDoesNotExist()
    }

    @Test
    fun visibleModalHidesTheComposedBackgroundFromAccessibility() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Box(
                    modifier = Modifier
                        .testTag("modal_background")
                        .accessibilityModalBackground(modalVisible = true)
                )
            }
        }

        composeTestRule.onNodeWithTag("modal_background", useUnmergedTree = true)
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.HideFromAccessibility,
                    Unit
                )
            )
    }

    @Test
    fun modalPaneAnnouncesItsUkrainianTitle() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Box(
                    modifier = Modifier
                        .testTag("modal_pane")
                        .accessibilityPane("Програвач")
                )
            }
        }

        composeTestRule.onNodeWithTag("modal_pane", useUnmergedTree = true)
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.PaneTitle,
                    "Програвач"
                )
            )
    }
}
