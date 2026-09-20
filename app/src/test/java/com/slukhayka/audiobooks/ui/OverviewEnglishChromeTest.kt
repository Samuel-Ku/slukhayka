package com.slukhayka.audiobooks.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.ingest.ChannelCardState
import com.slukhayka.audiobooks.data.ingest.ChannelTab
import com.slukhayka.audiobooks.testing.EnglishChromeWalk
import com.slukhayka.audiobooks.ui.screens.CatalogNavRow
import com.slukhayka.audiobooks.ui.screens.ChannelCardCallbacks
import com.slukhayka.audiobooks.ui.screens.ChannelImportCard
import com.slukhayka.audiobooks.ui.screens.CollectionsIndexContent
import com.slukhayka.audiobooks.ui.screens.DownloadQueueEmptyState
import com.slukhayka.audiobooks.ui.screens.ListenEmptyState
import com.slukhayka.audiobooks.ui.screens.collections.CuratorProfileContent
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * spec-46 T16 (#577) — the English run of the Огляд rails and the residual
 * empty states must be clean.
 *
 * The acceptance criterion is «Хром рейлів Огляду з ресурсів; EN-прогін
 * чистий». Asserting a handful of English labels would still pass with one
 * hardcoded Ukrainian string left in a corner, so the test walks the WHOLE
 * semantics tree and fails on any Cyrillic in the chrome — the
 * [LibraryEnglishChromeTest] pattern. Fixtures use Latin-only data, so a
 * failure can only come from chrome the app itself produced.
 *
 * #986 — the walk itself is the shared [EnglishChromeWalk], so it reads every
 * root and PaneTitle too; this slice used to keep a narrower private copy.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS", sdk = [36])
class OverviewEnglishChromeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val cyrillic = Regex("[А-Яа-яІіЇїЄєҐґ]")

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun theOverviewQuickLinksRailSpeaksEnglish() {
        composeTestRule.setContent {
            chrome {
                CatalogNavRow(
                    onTop100Click = {},
                    onPeopleClick = {},
                    onSeriesClick = {},
                    onCollectionsClick = {}
                )
            }
        }

        assertChromeHasNoCyrillic()
        listOf("Rating", "Narrators", "Authors", "Series", "Collections").forEach { label ->
            composeTestRule.onNodeWithText(label).assertExists()
        }
    }

    @Test
    fun theListenFreshInstallStateSpeaksEnglish() {
        composeTestRule.setContent {
            chrome { ListenEmptyState(onBrowseClick = {}, onImportClick = {}) }
        }

        assertChromeHasNoCyrillic()
        composeTestRule.onNodeWithText("Keep listening").assertExists()
    }

    @Test
    fun theCollectionsIndexPlaceholderSpeaksEnglish() {
        composeTestRule.setContent {
            chrome { CollectionsIndexContent(collections = emptyList(), onBookClick = {}) }
        }

        assertChromeHasNoCyrillic()
        composeTestRule
            .onNodeWithText("Collections will appear once the catalogue has loaded.")
            .assertExists()
    }

    @Test
    fun theDownloadManagerEmptyStateSpeaksEnglish() {
        composeTestRule.setContent {
            chrome { DownloadQueueEmptyState(modifier = Modifier.fillMaxSize()) }
        }

        assertChromeHasNoCyrillic()
        composeTestRule.onNodeWithText("No downloads").assertExists()
    }

    @Test
    fun theCuratorAndChannelEmptiesSpeakEnglish() {
        composeTestRule.setContent {
            chrome {
                Column {
                    CuratorProfileContent(pseudonym = "Curator", rows = emptyList(), onOpen = {})
                    ChannelImportCard(
                        state = ChannelCardState(
                            url = "https://www.youtube.com/@chan",
                            title = "Channel",
                            tab = ChannelTab.VIDEOS,
                            items = emptyList()
                        ),
                        callbacks = ChannelCardCallbacks(
                            onClose = {},
                            onRetryLoad = {},
                            onTabSelect = {},
                            onLoadMore = {},
                            onToggleItem = {},
                            onSelectLastN = {},
                            onToggleIncludeSkipped = {},
                            onStartImport = {},
                            onStopImport = {}
                        )
                    )
                }
            }
        }

        assertChromeHasNoCyrillic()
        composeTestRule.onNodeWithText("This curator has no visible collections yet").assertExists()
        composeTestRule.onNodeWithText("This tab is empty.").assertExists()
    }

    @Test
    fun theRecommendationConsentBodyHasAnEnglishTwin() {
        // Rendered only inside the endless home feed, which no unit test can
        // build cheaply; assert the resource itself is real English so the
        // body can never silently fall back to Ukrainian.
        val body = context.getString(R.string.feed_recommendation_consent_body)
        assertTrue("the consent body must be translated, not the UK fallback", body.isNotBlank())
        assertTrue("the consent body leaked Ukrainian into EN: $body", !cyrillic.containsMatchIn(body))
    }

    @Test
    fun thePersonPageChromeHasEnglishTwins() {
        // The person page's count subtitle and its empty/error states are
        // chrome the English run reaches on every person tap.
        listOf(
            context.getString(R.string.secondary_person_books_empty),
            context.getString(R.string.secondary_person_books_error)
        ).forEach { value ->
            assertTrue("person-page chrome leaked Ukrainian into EN: $value", !cyrillic.containsMatchIn(value))
        }
        listOf(2, 5).forEach { count ->
            val works = context.resources.getQuantityString(R.plurals.person_works_count, count, count)
            val narrations =
                context.resources.getQuantityString(R.plurals.person_narrations_count, count, count)
            assertTrue("work count leaked Ukrainian into EN: $works", !cyrillic.containsMatchIn(works))
            assertTrue("narration count leaked Ukrainian into EN: $narrations", !cyrillic.containsMatchIn(narrations))
        }
    }

    @Composable
    private fun chrome(content: @Composable () -> Unit) {
        AudiobookTheme(darkTheme = true) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                content()
            }
        }
    }

    private fun assertChromeHasNoCyrillic() {
        // #986 — one shared walk, every root, PaneTitle included. The local
        // `cyrillic` regex stays for the resource-string assertions, which
        // never touch the semantics tree.
        EnglishChromeWalk.assertNoCyrillic(composeTestRule, "overview")
    }
}
