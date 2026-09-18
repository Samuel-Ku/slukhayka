package com.slukhayka.audiobooks.ui.snapshots

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.source.GlobalSearchResult
import com.slukhayka.audiobooks.data.source.GlobalSearchSource
import com.slukhayka.audiobooks.ui.components.SectionHeaderTags
import com.slukhayka.audiobooks.ui.screens.PeopleNewArrivalsRail
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
 * #562 — the visible delta of the Огляд migration: the «Нове від ваших
 * авторів/виконавців» rail used to assert its own `titleMedium` bold title and
 * a free-standing count `TextButton`; it now renders the canonical SECTION
 * header with the count as its subtitle and mark-seen in the action slot.
 * The pin records that delta (JVM render, ADR-0017 evidence).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "uk-rUA-" + RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class PeopleNewArrivalsRailSnapshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val results = listOf(
        GlobalSearchResult(
            title = "Вкради мене... Зараз!",
            author = "Сергій Оріанець",
            mergeKey = "вкради-мене-зараз|сергій-оріанець",
            coverImageUrl = null,
            sources = listOf(GlobalSearchSource("4read", "4read", "https://4read.org/7611.html"))
        ),
        GlobalSearchResult(
            title = "Темна матерія",
            author = "Блейк Крауч",
            mergeKey = "темна-матерія|блейк-крауч",
            coverImageUrl = null,
            sources = listOf(GlobalSearchSource("soundbooks", "Sound-Books", "https://sound-books.net/temna"))
        )
    )

    @Test
    fun rail_renders_canonical_section_header_with_subtitle_counter() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        PeopleNewArrivalsRail(
                            results = results,
                            newCount = results.size,
                            onBookClick = {},
                            onMarkSeen = {}
                        )
                    }
                }
            }
        }

        composeTestRule.onNodeWithText("Нове від ваших авторів/виконавців")
            .assert(hasTestTag(SectionHeaderTags.SECTION))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
        val counter = ApplicationProvider.getApplicationContext<Context>()
            .resources.getQuantityString(R.plurals.home_people_new_count, results.size, results.size)
        composeTestRule.onNodeWithText(counter).assertExists()
        composeTestRule.onNodeWithTag("people_new_arrivals_badge").assertExists()

        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/people_new_arrivals_rail.png"
        )
    }
}
