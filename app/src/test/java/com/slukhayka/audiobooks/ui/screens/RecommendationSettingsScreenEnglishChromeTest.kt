package com.slukhayka.audiobooks.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.App
import com.slukhayka.audiobooks.testing.EnglishChromeWalk
import com.slukhayka.audiobooks.ui.MainViewModel
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile

/**
 * #1774 — the four «what I asked to hide» labels were a bare `when` returning
 * Ukrainian strings, and the model card's own copy had no EN/PL twins either.
 *
 * The installed-model marker keeps the screen off the network: the state card
 * then renders its «full model» line and the test proves the page's chrome is
 * English/Polish without waiting on a download.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS", sdk = [36])
class RecommendationSettingsScreenEnglishChromeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<App>()

    @Before
    fun markTheModelAsInstalled() {
        val dir = File(app.filesDir, "models/e5").apply { mkdirs() }
        RandomAccessFile(File(dir, "model.onnx"), "rw").use { it.setLength(1_100_000L) }
        File(dir, "tokenizer.json").writeText("{}")
    }

    private fun setScreen() {
        val viewModel = MainViewModel(app)
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Box(Modifier.width(411.dp).height(1200.dp)) {
                    RecommendationSettingsScreen(viewModel = viewModel, onBackClick = {})
                }
            }
        }
    }

    @Test
    fun recommendationSettingsSpeakEnglish() {
        setScreen()

        EnglishChromeWalk.assertNoCyrillic(composeTestRule, "recommendation settings")
        composeTestRule.onNodeWithText("Recommendation model").assertExists()
        composeTestRule.onNodeWithText("Learn only on this device").assertExists()
        composeTestRule.onNodeWithText("Help improve the shared model").assertExists()
    }

    @Test
    @Config(qualifiers = "pl-rPL")
    fun recommendationSettingsSpeakPolish() {
        setScreen()

        EnglishChromeWalk.assertNoCyrillic(composeTestRule, "recommendation settings (pl)")
        composeTestRule.onNodeWithText("Model rekomendacji").assertExists()
        composeTestRule.onNodeWithText("Ucz się tylko na tym urządzeniu").assertExists()
        composeTestRule.onNodeWithText("Pomóc ulepszać wspólny model").assertExists()
    }
}
