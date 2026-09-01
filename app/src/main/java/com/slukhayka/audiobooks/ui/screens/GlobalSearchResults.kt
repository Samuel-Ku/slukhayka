package com.slukhayka.audiobooks.ui.screens

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
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.source.GlobalSearchResult
import com.slukhayka.audiobooks.ui.MainViewModel
import com.slukhayka.audiobooks.ui.catalog.CatalogCardAction
import com.slukhayka.audiobooks.ui.catalog.CatalogCardActionState
import com.slukhayka.audiobooks.ui.components.BookCoverSemantics
import com.slukhayka.audiobooks.ui.theme.AppBadgeScrim
import com.slukhayka.audiobooks.ui.theme.AppBadgeScrimBorder
import com.slukhayka.audiobooks.ui.theme.AppDimens

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
    onPlayClick: () -> Unit = onClick,
    actionState: CatalogCardActionState = CatalogCardActionState.Idle,
    onCancelAction: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AppDimens.SpaceLg, vertical = AppDimens.SpaceSm)
            .testTag("global_search_result_${result.key}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onClick)
                .semantics {
                    contentDescription = "Відкрити книгу: ${result.title}"
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
        // Cover: the real artwork when the source carries one — the global
        // search used to render only the letter placeholder, so EVERY result
        // (4read, sound-books, audiobook-mp3, …) looked imageless
        // (2026-08-17 bug report). [CatalogCoverImage] falls back to its own
        // typographic placeholder when the cover is absent or fails to load;
        // the letter placeholder below stays for the (rare) blank-URL case.
        if (!result.coverImageUrl.isNullOrBlank()) {
            CatalogCoverImage(
                coverImageUrl = result.coverImageUrl,
                title = result.title,
                semantics = BookCoverSemantics.Decorative,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(AppDimens.RadiusCover))
            )
        } else {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(AppDimens.RadiusCover))
                    .clearAndSetSemantics { }
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = result.title.firstOrNull()?.toString()?.uppercase() ?: "?",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
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
            // Spec-30 T2 (#217): the resolved duration when one is known (the
            // local database or the shared metadata cache) — the search card
            // used to never show a duration at all.
            val duration = result.durationSeconds
            if (duration != null && duration > 0L) {
                Text(
                    text = MainViewModel.formatTime(duration),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
        }
        CatalogCardActionAffordance(
            title = result.title,
            cardKey = result.key,
            state = actionState,
            onPlay = onPlayClick,
            onCancel = onCancelAction
        )
    }
    CatalogCardStatus(result.key, actionState)
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
    onPlayClick: () -> Unit = onClick,
    actionState: CatalogCardActionState = CatalogCardActionState.Idle,
    onCancelAction: () -> Unit = {},
    modifier: Modifier = Modifier,
    downloadAllowed: Boolean = true,
    downloadProgress: Float? = null,
    isDownloaded: Boolean = false,
    onDownload: (() -> Unit)? = null
) {
    Column(modifier = modifier.width(120.dp).testTag("unified_catalog_${result.key}")) {
      Box {
       Column(
        modifier = Modifier
            .width(120.dp)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Відкрити книгу: ${result.title}" },
        horizontalAlignment = Alignment.CenterHorizontally
       ) {
        Box {
            CatalogCoverImage(
                coverImageUrl = result.coverImageUrl,
                title = result.title,
                semantics = BookCoverSemantics.Decorative,
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
                            .size(AppDimens.TouchTarget)
                            .clip(RoundedCornerShape(AppDimens.RadiusXs))
                            // Spec-22 T2: solid badge scrim over the cover — a
                            // translucent wash lost contrast on light artwork.
                            .background(AppBadgeScrim)
                            .border(1.dp, AppBadgeScrimBorder, RoundedCornerShape(AppDimens.RadiusXs))
                            .testTag("unified_catalog_download_${result.key}")
                    ) {
                        Icon(
                            imageVector = if (isDownloaded) Icons.Default.CloudDone else Icons.Default.CloudDownload,
                            contentDescription = stringResource(
                                if (isDownloaded) R.string.a11y_downloaded_work else R.string.a11y_download_work,
                                result.title
                            ),
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
       CatalogCardActionAffordance(
           title = result.title,
           cardKey = result.key,
           state = actionState,
           onPlay = onPlayClick,
           onCancel = onCancelAction,
           modifier = Modifier.align(Alignment.BottomEnd)
       )
      }
      CatalogCardStatus(result.key, actionState)
    }
}

@Composable
internal fun CatalogCardActionAffordance(
    title: String,
    cardKey: String,
    state: CatalogCardActionState,
    onPlay: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val checking = state is CatalogCardActionState.Checking && state.target.cardKey == cardKey
    IconButton(onClick = if (checking) onCancel else onPlay, modifier = modifier.size(AppDimens.TouchTarget)) {
        Icon(
            imageVector = if (checking) Icons.Default.Close else Icons.Default.PlayArrow,
            contentDescription = if (checking) {
                stringResource(R.string.catalog_card_cancel)
            } else {
                stringResource(R.string.a11y_catalog_card_listen, title)
            },
            tint = if (checking) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
internal fun CatalogCardStatus(cardKey: String, state: CatalogCardActionState) {
    val relevant = when (state) {
        is CatalogCardActionState.Checking -> state.target.cardKey == cardKey
        is CatalogCardActionState.Failed -> state.target.cardKey == cardKey
        is CatalogCardActionState.BrowserRequired -> state.target.cardKey == cardKey
        is CatalogCardActionState.Cancelled -> state.target.cardKey == cardKey
        else -> false
    }
    if (!relevant) return
    val text = when (state) {
        is CatalogCardActionState.Checking -> stringResource(R.string.catalog_card_checking)
        is CatalogCardActionState.Failed -> stringResource(
            if (state.action == CatalogCardAction.OPEN) R.string.catalog_card_open_error
            else R.string.catalog_card_play_error
        )
        is CatalogCardActionState.BrowserRequired -> stringResource(R.string.catalog_card_browser_required)
        is CatalogCardActionState.Cancelled -> stringResource(R.string.catalog_card_cancelled)
        else -> return
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = if (state is CatalogCardActionState.Failed) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    )
}
