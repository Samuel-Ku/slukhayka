package com.example.ui.snapshots

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.example.ui.components.AppSectionHeader
import com.example.ui.components.EmptyState
import com.example.ui.components.EmptyStateRow
import com.example.ui.theme.AudiobookTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Snapshot tests for the design-system primitives (wayfinder #23): the
 * section header, the full-size empty state and the compact empty-state row,
 * rendered in both the dark (graphite-navy) and light (warm paper) schemes.
 * Verifies the light scheme actually renders before the themes ticket (#37)
 * exposes it to users.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class DesignSystemSnapshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun section_header_dark() {
        composeTestRule.setContent {
            AudiobookTheme {
                DesignSurface {
                    AppSectionHeader(title = "Нещодавно слухали")
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/ds_section_header_dark.png"
        )
    }

    @Test
    fun empty_state_full_dark() {
        composeTestRule.setContent {
            AudiobookTheme {
                DesignSurface {
                    EmptyState(
                        icon = Icons.Default.PlayCircle,
                        title = "Продовжити слухати",
                        body = "Тут з'явиться ваша поточна книга, щойно ви почнете слухати."
                    ) {
                        Button(
                            onClick = {},
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            modifier = Modifier
                                .padding(horizontal = 24.dp)
                                .height(48.dp)
                                .testTag("ds_empty_cta")
                        ) {
                            Text("Переглянути каталог")
                        }
                    }
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/ds_empty_state_full_dark.png"
        )
    }

    @Test
    fun empty_state_compact_dark() {
        composeTestRule.setContent {
            AudiobookTheme {
                DesignSurface {
                    EmptyStateRow(
                        icon = Icons.Default.MenuBook,
                        title = "Завантажених книг немає",
                        body = "Книги з'являться тут після завантаження."
                    )
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/ds_empty_state_compact_dark.png"
        )
    }

    @Test
    fun empty_state_full_light() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = false) {
                DesignSurface {
                    EmptyState(
                        icon = Icons.Default.Explore,
                        title = "Знайти книгу",
                        body = "Перегляньте каталог або додайте власні файли."
                    )
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/ds_empty_state_full_light.png"
        )
    }
}

/** Chrome matching the scheme being tested: full size, scheme background. */
@Composable
private fun DesignSurface(content: @Composable () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) { content() }
    }
}
