package com.slukhayka.audiobooks.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertTopPositionInRootIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
}
