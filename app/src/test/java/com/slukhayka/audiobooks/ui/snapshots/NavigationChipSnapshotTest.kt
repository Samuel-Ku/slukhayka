package com.slukhayka.audiobooks.ui.snapshots

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.slukhayka.audiobooks.ui.screens.CatalogNavRow
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * spec-28 (#198) — snapshot pins for the Огляд navigation row: the five
 * [com.slukhayka.audiobooks.ui.components.NavigationChip]s (ТОП 100 /
 * Виконавці / Автори / Серії / Колекції). Filled, outline-free — the
 * «перейти» form per ADR-0018 — and each chip forwards its tap. Stateless
 * pure-`@Composable` inputs, no `MainViewModel`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class NavigationChipSnapshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun navigation_chip_row() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                CatalogSurface {
                    CatalogNavRow(
                        onTop100Click = {},
                        onPeopleClick = {},
                        onSeriesClick = {},
                        onCollectionsClick = {}
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("ТОП 100").assertExists()
        composeTestRule.onNodeWithText("Серії").assertExists()
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/navigation_chip_row.png"
        )
    }

    @Test
    fun navigation_chip_tap_forwards() {
        var opened: String? = null
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                CatalogSurface {
                    CatalogNavRow(
                        onTop100Click = { opened = "top100" },
                        onPeopleClick = {},
                        onSeriesClick = { opened = "series" },
                        onCollectionsClick = {}
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("ТОП 100").performClick()
        assertEquals("top100", opened)

        composeTestRule.onNodeWithText("Серії").performClick()
        assertEquals("series", opened)
    }
}

/** Same chrome as the other snapshot seams: scheme background, full size. */
@Composable
private fun CatalogSurface(content: @Composable () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) { content() }
    }
}
