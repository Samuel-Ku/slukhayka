package com.slukhayka.audiobooks.ui.snapshots

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.slukhayka.audiobooks.data.recommend.RecommendationEngine
import com.slukhayka.audiobooks.ui.screens.RecommendedBookCard
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
 * Snapshot pin for the spec-19 «Рекомендовано для вас» card: title, author
 * and the explanation chip («схоже на X»). Same Robolectric + roborazzi
 * pattern as the other Огляд row snapshots (CatalogRowsSnapshotTest) — pure
 * `@Composable` input, no `MainViewModel`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class RecommendedBookCardSnapshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun recommended_book_card_with_reason_chip() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                RecommendedSurface {
                    RecommendedBookCard(
                        rec = RecommendationEngine.Recommendation(
                            candidate = RecommendationEngine.Candidate(
                                id = "4read-1234-tini-zabutyh-predkiv",
                                title = "Тіні забутих предків",
                                author = "Михайло Коцюбинський"
                            ),
                            score = 0.82,
                            reasonTitle = "Лісова пісня"
                        ),
                        onClick = {}
                    )
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/recommended_book_card.png"
        )
    }
}

/** Same chrome as the other row snapshots: scheme background, full size. */
@Composable
private fun RecommendedSurface(content: @Composable () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) { content() }
    }
}
