package com.example.ui.snapshots

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import com.example.data.source.GlobalSearchResult
import com.example.data.source.GlobalSearchSource
import com.example.ui.screens.UnifiedCatalogCard
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
 * Snapshot tests for the spec-15 T1/T4 unified catalogue card: the cover-first
 * «Увесь каталог» card with its one-tap download affordance — affordance on a
 * download-allowed source, the progress bar while downloading, and the quiet
 * CloudDone state for an already-downloaded book. Pure `@Composable` inputs —
 * no `MainViewModel`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class UnifiedCatalogCardSnapshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val result = GlobalSearchResult(
        title = "Пасажир",
        author = "Жан-Крістоф Гранже",
        narrator = "",
        mergeKey = "пасажир|жанкрісторгранже",
        coverImageUrl = null,
        sources = listOf(GlobalSearchSource("sluhay", "Sluhay", "https://sluhay.com/pasazhir.html"))
    )

    private fun row(content: @Composable () -> Unit) {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(16.dp)) {
                        content()
                    }
                }
            }
        }
    }

    @Test
    fun unified_catalog_card_download_affordance() {
        row {
            UnifiedCatalogCard(
                result = result,
                onClick = {},
                downloadAllowed = true,
                isDownloaded = false,
                onDownload = {}
            )
        }
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/unified_catalog_download_affordance.png"
        )
    }

    @Test
    fun unified_catalog_card_download_progress() {
        row {
            UnifiedCatalogCard(
                result = result,
                onClick = {},
                downloadAllowed = true,
                downloadProgress = 0.45f,
                onDownload = {}
            )
        }
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/unified_catalog_download_progress.png"
        )
    }

    @Test
    fun unified_catalog_card_downloaded() {
        row {
            UnifiedCatalogCard(
                result = result,
                onClick = {},
                downloadAllowed = true,
                isDownloaded = true,
                onDownload = {}
            )
        }
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/unified_catalog_downloaded.png"
        )
    }

    @Test
    fun unified_catalog_card_stream_only_no_affordance() {
        row {
            UnifiedCatalogCard(
                result = result.copy(
                    sources = listOf(GlobalSearchSource("lihtar", "Lihtar", "https://lihtar.in.ua/pasazhir"))
                ),
                onClick = {},
                downloadAllowed = false,
                isDownloaded = false,
                onDownload = {}
            )
        }
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/unified_catalog_stream_only.png"
        )
    }

    @Test
    fun unified_catalog_card_layout() {
        row {
            Column {
                Text("Card layout baseline", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(8.dp))
                UnifiedCatalogCard(result = result, onClick = {}, downloadAllowed = false)
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/unified_catalog_card.png"
        )
    }
}
