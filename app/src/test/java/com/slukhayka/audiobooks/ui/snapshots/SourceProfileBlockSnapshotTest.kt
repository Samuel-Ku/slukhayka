package com.slukhayka.audiobooks.ui.snapshots

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.data.entries.LibraryEntries.SourceProfile
import com.slukhayka.audiobooks.ui.screens.SourceProfileBlock
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Snapshot tests for the spec-15 T5 per-source detail block: what ONE source
 * says about the book — description, rating, narrator, genres — under the
 * source's badge. Pure `@Composable` inputs, no ViewModel.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class SourceProfileBlockSnapshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun source_profile_full() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                ProfileSurface {
                    SourceProfileBlock(
                        profile = SourceProfile(
                            sourceId = "4read",
                            sourceName = "4read",
                            url = "https://4read.org/pasazhir.html",
                            description = "Перший роман циклу про комісара Греньє. Нічний потяг, зниклий пасажир і слід, що веде в минуле.",
                            rating = 4.8,
                            narrator = "Валерій Завалко",
                            genres = listOf("Зарубіжна література", "Детектив")
                        )
                    )
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/source_profile_full.png"
        )
    }

    @Test
    fun source_profile_minimal() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                ProfileSurface {
                    SourceProfileBlock(
                        profile = SourceProfile(
                            sourceId = "sluhay",
                            sourceName = "Sluhay",
                            url = "https://sluhay.com/pasazhir.html",
                            description = "Париж, нічний потяг і зниклий пасажир."
                        )
                    )
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/source_profile_minimal.png"
        )
    }
}

/** Same chrome as the other snapshot tests: scheme background, full size. */
@Composable
private fun ProfileSurface(content: @Composable () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Що кажуть джерела", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}
