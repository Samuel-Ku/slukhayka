package com.slukhayka.audiobooks.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.player.PlayerState
import com.slukhayka.audiobooks.ui.components.PlayerDebugOverlay
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlayerDebugOverlayAccessibilityTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val work = AudiobookEntity(
        id = "debug-work",
        title = "Кобзар",
        author = "Тарас Шевченко",
        narrator = "",
        description = "",
        coverDrawableRes = 0,
        genre = "Класика",
        sourceUrl = "https://example.invalid/kobzar"
    )

    @Test
    fun debugActionsNameTheWorkUseUkrainianAndKeepFullTouchTargets() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PlayerDebugOverlay(
                        playerState = PlayerState(
                            currentBook = work,
                            currentStreamUrl = "https://audio.example.invalid/kobzar.mp3"
                        ),
                        onClose = {},
                        onRetryPlayback = {},
                        metricsSummary = "starts=1",
                        journalExport = "prepared"
                    )
                }
            }
        }

        assertFullTarget("Згорнути діагностику відтворення")
        assertFullTarget("Закрити діагностику відтворення")
        assertFullTarget("Копіювати адресу потоку для «Кобзар»")
        assertFullTarget("Копіювати журнал відтворення для «Кобзар»")
        assertFullTarget("Повторити завантаження аудіо для «Кобзар»")

        composeTestRule.onNodeWithContentDescription("Debug Icon").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Згорнути діагностику відтворення")
            .performClick()
        composeTestRule.onNodeWithContentDescription("Розгорнути діагностику відтворення")
            .assertExists()
    }

    private fun assertFullTarget(description: String) {
        composeTestRule.onNodeWithContentDescription(description)
            .assertExists()
            .performScrollTo()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
    }
}
