package com.slukhayka.audiobooks.ui.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SpeedSheetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun exposesTheFineGrainedExactSpeedBetweenPresets() {
        var changedSpeed = 0f
        composeTestRule.setContent {
            TestTheme {
                SpeedSheet(
                    currentSpeed = 1.0f,
                    onSpeedChange = { changedSpeed = it },
                    onSetDefault = {},
                    onDismiss = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("exact_speed_slider")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ProgressBarRangeInfo,
                    ProgressBarRangeInfo(1.0f, 0.5f..3.0f, 49)
                )
            )
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
                setProgress(1.15f)
            }

        assertEquals(1.15f, changedSpeed, 0.0001f)
    }

    @Test
    fun formatsSliderFloatsWithoutTruncatingThem() {
        assertEquals("1.1", formatSpeed(1.0999999f))
        assertEquals("1.15", formatSpeed(1.1500001f))
        assertEquals("1.1", formatSpeed(1.1f))
        assertEquals("0.5", formatSpeed(0.5f))
        assertEquals("3.0", formatSpeed(3.0f))
        assertEquals("2.05", formatSpeed(2.05f))
        assertEquals("1,1", formatSpeedForSpeech(1.1f))
        assertEquals("1,15", formatSpeedForSpeech(1.1500001f))
    }

    @Composable
    private fun TestTheme(content: @Composable () -> Unit) {
        AudiobookTheme(darkTheme = true) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                content()
            }
        }
    }
}
