package com.slukhayka.audiobooks.ui

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.ingest.ChannelCardState
import com.slukhayka.audiobooks.data.ingest.ChannelTab
import com.slukhayka.audiobooks.ui.screens.ChannelCardCallbacks
import com.slukhayka.audiobooks.ui.screens.ChannelImportCard
import com.slukhayka.audiobooks.ui.screens.DownloadQueueEmptyState
import com.slukhayka.audiobooks.ui.screens.EmptyCatalogState
import com.slukhayka.audiobooks.ui.screens.collections.CuratorProfileContent
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * spec-46 T16 (#577) — the residual surfaces' empty states are the canonical
 * ones.
 *
 * Acceptance: «Порожні стани вкладок і залишкових поверхонь — канонічні
 * (жодних голих Text/Box)». The behaviour that proves it is the one the
 * canonical states own and a bare `Text` never had: an empty state is
 * transient, so its title is a polite live-region that TalkBack announces the
 * moment it appears (v1.4 C4, ADR-0033). Each test also pins the human
 * contract the surface already had — the message and the next action.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "uk-rUA-w411dp-h891dp")
class CanonicalResidualEmptyStatesTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun string(id: Int): String = context.getString(id)

    /** The canonical empty state is a polite live-region; a bare Text is not. */
    private fun announcesItself() = SemanticsMatcher.expectValue(
        SemanticsProperties.LiveRegion,
        LiveRegionMode.Polite
    )

    @Test
    fun `a curator with nothing visible renders the canonical compact state`() {
        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                CuratorProfileContent(pseudonym = "Куратор", rows = emptyList(), onOpen = {})
            }
        }

        compose.onNodeWithTag("curator_profile_empty").assertIsDisplayed()
        compose.onNodeWithText(string(R.string.curator_profile_empty)).assert(announcesItself())
    }

    @Test
    fun `the download manager empty state is the canonical full state`() {
        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                DownloadQueueEmptyState(modifier = Modifier.fillMaxSize())
            }
        }

        compose.onNodeWithTag("download_queue_empty").assertIsDisplayed()
        compose.onNodeWithText(string(R.string.download_manager_empty_title))
            .assert(announcesItself())
        compose.onNodeWithText(string(R.string.download_manager_empty_body)).assertIsDisplayed()
    }

    @Test
    fun `the empty catalogue is canonical and keeps both next actions`() {
        var refreshed = false
        var imported = false
        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    EmptyCatalogState(
                        onRefreshClick = { refreshed = true },
                        onImportClick = { imported = true }
                    )
                }
            }
        }

        compose.onNodeWithText(string(R.string.home_empty_catalog_title)).assert(announcesItself())
        compose.onNodeWithText(string(R.string.home_empty_catalog_body)).assertIsDisplayed()
        compose.onNodeWithTag("catalog_empty_refresh").performClick()
        compose.onNodeWithTag("catalog_empty_import").performClick()
        assertTrue("refresh action must survive the migration", refreshed)
        assertTrue("import action must survive the migration", imported)
    }

    @Test
    fun `an empty channel tab is the canonical compact state`() {
        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                ChannelImportCard(state = channel(), callbacks = callbacks())
            }
        }

        compose.onNodeWithTag("channel_empty").assertIsDisplayed()
        compose.onNodeWithText(string(R.string.submission_channel_empty)).assert(announcesItself())
    }

    @Test
    fun `a failed channel load is canonical and keeps its retry`() {
        var retried = false
        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                ChannelImportCard(
                    state = channel(loadFailed = true),
                    callbacks = callbacks(onRetryLoad = { retried = true })
                )
            }
        }

        compose.onNodeWithTag("channel_failed").assertIsDisplayed()
        compose.onNodeWithText(string(R.string.submission_channel_failed)).assert(announcesItself())
        compose.onNodeWithTag("channel_retry").performClick()
        assertTrue("retry must survive the migration", retried)
    }

    private fun channel(loadFailed: Boolean = false) = ChannelCardState(
        url = "https://www.youtube.com/@chan",
        title = "Канал",
        tab = ChannelTab.VIDEOS,
        items = emptyList(),
        loadFailed = loadFailed
    )

    private fun callbacks(onRetryLoad: () -> Unit = {}) = ChannelCardCallbacks(
        onClose = {},
        onRetryLoad = onRetryLoad,
        onTabSelect = {},
        onLoadMore = {},
        onToggleItem = {},
        onSelectLastN = {},
        onToggleIncludeSkipped = {},
        onStartImport = {},
        onStopImport = {}
    )
}
