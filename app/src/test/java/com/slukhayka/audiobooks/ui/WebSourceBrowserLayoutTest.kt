package com.slukhayka.audiobooks.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertTopPositionInRootIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.App
import com.slukhayka.audiobooks.ui.screens.WebSourceBrowserScreen
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WebSourceBrowserLayoutTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun narrowDebugBrowserKeepsTheCloseActionAtTheTop() {
        val app = ApplicationProvider.getApplicationContext<App>()
        val viewModel = MainViewModel(app)

        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Box(Modifier.width(411.dp).height(900.dp)) {
                    WebSourceBrowserScreen(
                        viewModel = viewModel,
                        sourceId = "4read",
                        homeUrl = "https://4read.org/",
                        displayName = "4read",
                        onClose = {}
                    )
                }
            }
        }

        composeTestRule.onNodeWithContentDescription("Закрити браузер джерела «4read»")
            .assertTopPositionInRootIsEqualTo(12.dp)
    }

    @Test
    fun theBrowserOffersTheCategoryDoorToOglad() {
        val app = ApplicationProvider.getApplicationContext<App>()
        val viewModel = MainViewModel(app)

        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Box(Modifier.width(411.dp).height(900.dp)) {
                    WebSourceBrowserScreen(
                        viewModel = viewModel,
                        sourceId = "soundbooks",
                        homeUrl = "https://sound-books.net/",
                        displayName = "Sound-Books",
                        onClose = {}
                    )
                }
            }
        }

        // #528 — ONE category action; the result line reports what happened.
        composeTestRule.onNodeWithContentDescription("Додати категорію до Огляду")
            .assertExists()
            .performClick()
        composeTestRule.onNodeWithTag("browser_category_result")
            .assertTextContains("Додаю категорію до Огляду", substring = true)
    }
}
