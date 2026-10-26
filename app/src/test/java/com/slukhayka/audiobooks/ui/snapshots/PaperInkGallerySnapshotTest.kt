package com.slukhayka.audiobooks.ui.snapshots

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import com.slukhayka.audiobooks.ui.components.paperink.PaperInkBookRow
import com.slukhayka.audiobooks.ui.components.paperink.PaperInkHeroCard
import com.slukhayka.audiobooks.ui.components.paperink.PaperInkMiniPlayerBar
import com.slukhayka.audiobooks.ui.components.paperink.PaperInkNavItem
import com.slukhayka.audiobooks.ui.components.paperink.PaperInkNavigationBar
import com.slukhayka.audiobooks.ui.components.paperink.PaperInkPosterCard
import com.slukhayka.audiobooks.ui.components.paperink.PaperInkScreenTitle
import com.slukhayka.audiobooks.ui.components.paperink.PaperInkSectionHeader
import com.slukhayka.audiobooks.ui.theme.PaperInkTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The «Папір і голос» primitive set, rendered in real Compose before it is
 * wired into a screen.
 *
 * This is the first place the direction becomes code rather than a browser
 * mockup: Onest/Literata metrics, the paper/ink schemes and the measured sizes
 * are exercised together under Robolectric's native renderer. The primitives are
 * additive — no existing screen uses them yet, so no committed golden moves;
 * the migration swaps screens onto them and re-records only those screens.
 *
 * Four images: cards and rows/chrome, each in both schemes. They are goldens,
 * not throwaway previews: a later refactor that breaks the shelf rhythm or the
 * mini-player ring fails here.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "uk-rUA-" + RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class PaperInkGallerySnapshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val navItems = listOf(
        PaperInkNavItem("Слухати", Icons.Filled.Headphones),
        PaperInkNavItem("Огляд", Icons.Filled.Explore),
        PaperInkNavItem("Медіатека", Icons.Filled.LibraryMusic),
        PaperInkNavItem("Друзі", Icons.Filled.Group),
    )

    @Composable
    private fun GallerySurface(darkTheme: Boolean, content: @Composable () -> Unit) {
        PaperInkTheme(darkTheme = darkTheme) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    content()
                }
            }
        }
    }

    @Composable
    private fun CardsGallery(darkTheme: Boolean) {
        GallerySurface(darkTheme) {
            PaperInkScreenTitle("Слухати", modifier = Modifier.padding(bottom = 4.dp))
            PaperInkHeroCard(
                title = "Трохи ненависті",
                author = "Джо Аберкромбі",
                narrator = "читає Рік САН4Е3",
                chapterLabel = "Розділ 30 · 12:08 із 42:59",
                remainingLabel = "Залишилось 9 год 23 хв",
                progress = 0.28f,
                onPlayClick = {},
            )
            PaperInkSectionHeader(title = "Продовжити серію")
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                PaperInkPosterCard(
                    title = "Проблема з миром",
                    author = "Джо Аберкромбі",
                    meta = "17 год 59 хв",
                )
                PaperInkPosterCard(
                    title = "Епоха божевілля",
                    author = "Джо Аберкромбі",
                    meta = "18 год 20 хв",
                    progress = 0.12f,
                    nowPlaying = true,
                )
            }
        }
    }

    @Composable
    private fun RowsGallery(darkTheme: Boolean) {
        GallerySurface(darkTheme) {
            PaperInkSectionHeader(title = "Медіатека", action = "Сортувати", onActionClick = {})
            PaperInkBookRow(
                title = "Трохи ненависті",
                author = "Джо Аберкромбі",
                series = "Епоха божевілля · Книга 1",
                progress = 0.28f,
                remainingLabel = "Залишилось 9 год 23 хв",
                sourceLabel = "4read",
                showDivider = false,
            )
            PaperInkBookRow(
                title = "Вкради мене… Зараз!",
                author = "Сергій Оріанець",
                series = "Оповідання · 55 хв",
                progress = 0.68f,
                remainingLabel = "Залишилось 18 хв",
                sourceLabel = "sluhay",
            )
            PaperInkMiniPlayerBar(
                title = "Трохи ненависті",
                meta = "Розділ 30 · 12:08 із 42:59",
                progress = 0.28f,
                isPlaying = true,
                onPlayPauseClick = {},
            )
            PaperInkNavigationBar(
                items = navItems,
                selectedIndex = 2,
                onSelect = {},
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }

    @Test
    fun paperink_cards_dark() {
        composeTestRule.setContent { CardsGallery(darkTheme = true) }
        composeTestRule.onNodeWithText("Слухати").assertExists()
        composeTestRule.onRoot().captureRoboImage("src/test/snapshots/paperink_cards_dark.png")
    }

    @Test
    fun paperink_cards_light() {
        composeTestRule.setContent { CardsGallery(darkTheme = false) }
        composeTestRule.onNodeWithText("Залишилось 9 год 23 хв").assertExists()
        composeTestRule.onRoot().captureRoboImage("src/test/snapshots/paperink_cards_light.png")
    }

    @Test
    fun paperink_rows_chrome_dark() {
        composeTestRule.setContent { RowsGallery(darkTheme = true) }
        composeTestRule.onNodeWithText("Сергій Оріанець").assertExists()
        composeTestRule.onRoot().captureRoboImage("src/test/snapshots/paperink_rows_chrome_dark.png")
    }

    @Test
    fun paperink_rows_chrome_light() {
        composeTestRule.setContent { RowsGallery(darkTheme = false) }
        composeTestRule.onNodeWithText("Залишилось 18 хв").assertExists()
        composeTestRule.onRoot().captureRoboImage("src/test/snapshots/paperink_rows_chrome_light.png")
    }
}
