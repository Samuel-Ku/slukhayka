package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import com.example.data.source.GlobalSearchResult
import com.example.ui.theme.AppBadgeScrim
import com.example.ui.theme.AppBadgeScrimBorder
import com.example.ui.theme.AppDimens

/**
 * Spec-10 T4 — one global-search result card: a Work with a badge per source
 * that matched. Tapping imports from the found source and plays. Extracted as
 * a pure `@Composable` (no ViewModel) so the snapshot seam can pin the layout
 * and badges without a network or a database.
 */
@Composable
fun GlobalSearchResultCard(
    result: GlobalSearchResult,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = AppDimens.SpaceLg, vertical = AppDimens.SpaceSm)
            .testTag("global_search_result_${result.key}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Cover placeholder: the first letter on the surface container.
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(AppDimens.RadiusCover))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = result.title.firstOrNull()?.toString()?.uppercase() ?: "?",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(AppDimens.SpaceMd))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = result.title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (result.author.isNotBlank()) {
                Text(
                    text = result.author,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (result.sources.isNotEmpty()) {
                Spacer(modifier = Modifier.height(AppDimens.SpaceXs))
                Row(horizontalArrangement = Arrangement.spacedBy(AppDimens.SpaceXs)) {
                    result.sources.forEach { source ->
                        SourceBadgePill(label = source.sourceName)
                    }
                }
            }
        }
        Icon(
            imageVector = Icons.Default.PlayArrow,
            contentDescription = "Відтворити",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(32.dp)
        )
    }
}

/** Small unobtrusive pill: which source(s) carry a book (spec-10 T4). */
@Composable
fun SourceBadgePill(
    label: String,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(AppDimens.RadiusXs),
        modifier = modifier
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

/**
 * Spec-15 T1 — one cover-first card of the deduplicated «Увесь каталог»
 * union: a Work with a badge per source that carries it. Tapping imports from
 * the first found source and plays (same behaviour as the global-search
 * cards). Pure `@Composable` (no ViewModel) so the snapshot seam can pin it
 * from fixture data.
 *
 * Spec-15 T4 — the card also carries a one-tap download affordance (a small
 * icon on the cover). [downloadAllowed] hides it for stream-only sources;
 * [downloadProgress] turns it into a progress bar while the book downloads;
 * [isDownloaded] marks the book as offline-ready (CloudDone).
 */
@Composable
fun UnifiedCatalogCard(
    result: GlobalSearchResult,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    downloadAllowed: Boolean = true,
    downloadProgress: Float? = null,
    isDownloaded: Boolean = false,
    onDownload: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .width(120.dp)
            .clickable(onClick = onClick)
            .testTag("unified_catalog_${result.key}"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box {
            CatalogCoverImage(
                coverImageUrl = result.coverImageUrl,
                title = result.title,
                modifier = Modifier
                    .width(120.dp)
                    .height(168.dp)
                    .clip(RoundedCornerShape(AppDimens.RadiusCardLg))
            )
            if (downloadAllowed && onDownload != null) {
                val progress = downloadProgress
                if (progress != null) {
                    // Downloading: a thin progress bar along the cover's bottom
                    // edge. The card recomposes as chapters complete (the
                    // repository writes downloadProgress per chapter).
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(AppDimens.RadiusXs))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progress.coerceIn(0f, 1f))
                                .height(4.dp)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                } else {
                    IconButton(
                        onClick = onDownload,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(32.dp)
                            .clip(RoundedCornerShape(AppDimens.RadiusXs))
                            // Spec-22 T2: solid badge scrim over the cover — a
                            // translucent wash lost contrast on light artwork.
                            .background(AppBadgeScrim)
                            .border(1.dp, AppBadgeScrimBorder, RoundedCornerShape(AppDimens.RadiusXs))
                            .testTag("unified_catalog_download_${result.key}")
                    ) {
                        Icon(
                            imageVector = if (isDownloaded) Icons.Default.CloudDone else Icons.Default.CloudDownload,
                            contentDescription = if (isDownloaded) "Завантажено" else "Завантажити",
                            tint = if (isDownloaded) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = result.title,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        if (result.author.isNotBlank()) {
            Text(
                text = result.author,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (result.sources.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(AppDimens.SpaceXs)) {
                result.sources.forEach { source ->
                    SourceBadgePill(label = source.sourceName)
                }
            }
        }
    }
}
