package com.slukhayka.audiobooks.ui

import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.ui.screens.PersonBookmarkButton
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PersonBookmarkButtonTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun tapTogglesBookmark() {
        var toggled = false

        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface {
                    PersonBookmarkButton(
                        isBookmarked = false,
                        notifyEnabled = true,
                        personName = "Тестовий Автор",
                        onToggle = { toggled = true },
                        onToggleNotify = {}
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag("person_bookmark_button").performClick()
        assertTrue(toggled)
    }

    @Test
    fun unbookmarkedShowsStarBorder() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface {
                    PersonBookmarkButton(
                        isBookmarked = false,
                        notifyEnabled = true,
                        personName = "Тестовий Автор",
                        onToggle = {},
                        onToggleNotify = {}
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag("person_bookmark_button")
            .assertIsDisplayed()
    }

    @Test
    fun bookmarkedShowsFilledStar() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface {
                    PersonBookmarkButton(
                        isBookmarked = true,
                        notifyEnabled = true,
                        personName = "Тестовий Автор",
                        onToggle = {},
                        onToggleNotify = {}
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag("person_bookmark_button")
            .assertIsDisplayed()
    }

    @Test
    fun touchTargetIsAtLeast48dp() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface {
                    PersonBookmarkButton(
                        isBookmarked = false,
                        notifyEnabled = true,
                        personName = "Тестовий Автор",
                        onToggle = {},
                        onToggleNotify = {}
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag("person_bookmark_button")
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun touchTargetIsAtLeast48dpAtFontScale115() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 1.15f)) {
                    Surface {
                        PersonBookmarkButton(
                            isBookmarked = false,
                            notifyEnabled = true,
                            personName = "Тестовий Автор",
                            onToggle = {},
                            onToggleNotify = {}
                        )
                    }
                }
            }
        }

        composeTestRule.onNodeWithTag("person_bookmark_button")
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun accessibilitySemanticsAreCorrect() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface {
                    PersonBookmarkButton(
                        isBookmarked = false,
                        notifyEnabled = true,
                        personName = "Тестовий Автор",
                        onToggle = {},
                        onToggleNotify = {}
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag("person_bookmark_button")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.Role,
                    androidx.compose.ui.semantics.Role.Button
                )
            )
    }

    @Test
    fun buttonIsClickableWhenBookmarked() {
        var toggled = false

        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface {
                    PersonBookmarkButton(
                        isBookmarked = true,
                        notifyEnabled = true,
                        personName = "Тестовий Автор",
                        onToggle = { toggled = true },
                        onToggleNotify = {}
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag("person_bookmark_button").performClick()
        assertTrue(toggled)
    }

    @Test
    fun longPressChangesNotificationWithoutRemovingBookmark() {
        var bookmarkToggled = false
        var notifyEnabled: Boolean? = null

        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface {
                    PersonBookmarkButton(
                        isBookmarked = true,
                        notifyEnabled = true,
                        personName = "Тестовий Автор",
                        onToggle = { bookmarkToggled = true },
                        onToggleNotify = { notifyEnabled = it }
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag("person_bookmark_button")
            .performTouchInput { longClick() }
        composeTestRule.onNodeWithTag("person_bookmark_notify_toggle")
            .assertIsDisplayed()
            .performClick()

        assertFalse(bookmarkToggled)
        assertFalse(notifyEnabled ?: true)
    }

    @Test
    fun talkBackContentDescription_addWhenNotBookmarked() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface {
                    PersonBookmarkButton(
                        isBookmarked = false,
                        notifyEnabled = true,
                        personName = "Тестовий Автор",
                        onToggle = {},
                        onToggleNotify = {}
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag("person_bookmark_button").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ContentDescription,
                listOf("Додати «Тестовий Автор» в закладки")
            )
        )
    }

    @Test
    fun talkBackContentDescription_removeWhenBookmarked() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface {
                    PersonBookmarkButton(
                        isBookmarked = true,
                        notifyEnabled = true,
                        personName = "Тестовий Автор",
                        onToggle = {},
                        onToggleNotify = {}
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag("person_bookmark_button").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ContentDescription,
                listOf("Прибрати «Тестовий Автор» з закладок")
            )
        )
    }

    @Test
    fun talkBackStateDescription_offWhenNotBookmarked() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface {
                    PersonBookmarkButton(
                        isBookmarked = false,
                        notifyEnabled = true,
                        personName = "Тестовий Автор",
                        onToggle = {},
                        onToggleNotify = {}
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag("person_bookmark_button").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                "Не в закладках"
            )
        )
    }

    @Test
    fun talkBackStateDescription_onWhenBookmarked() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface {
                    PersonBookmarkButton(
                        isBookmarked = true,
                        notifyEnabled = false,
                        personName = "Тестовий Автор",
                        onToggle = {},
                        onToggleNotify = {}
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag("person_bookmark_button").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                "В закладках"
            )
        )
    }
}
