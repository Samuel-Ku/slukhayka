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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import com.slukhayka.audiobooks.ui.catalog.CatalogCardFailure
import com.slukhayka.audiobooks.ui.catalog.CatalogBrowserFocusReturn
import com.slukhayka.audiobooks.ui.components.BookCoverSemantics
import com.slukhayka.audiobooks.ui.components.BookRow
import com.slukhayka.audiobooks.ui.components.CatalogCoverImage
import com.slukhayka.audiobooks.ui.components.MetadataChip
import com.slukhayka.audiobooks.ui.components.formatRowDuration
import com.slukhayka.audiobooks.ui.theme.AppBadgeScrim
import com.slukhayka.audiobooks.ui.theme.AppBadgeScrimBorder
import com.slukhayka.audiobooks.ui.theme.AppDimens

/**
 * Spec-10 T4 — one global-search result card: a Work with a badge per source
 * that matched. Tapping opens the book page (ADR-0018: shelves carry no
 * one-tap play). Extracted as a pure `@Composable` (no ViewModel) so the
 * snapshot seam can pin the layout and badges without a network or a
 * database.
 */
@Composable
fun GlobalSearchResultCard(
    result: GlobalSearchResult,
    onClick: () -> Unit,
    actionState: CatalogCardActionState = CatalogCardActionState.Idle,
    onOpenBrowser: () -> Unit = {},
    onPreflight: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    LaunchedEffect(result.key) { onPreflight() }
    // v1.4 C3/C4 (ADR-0033): the result row IS the canonical BookRow with
    // MetadataChip slots — the old bordered-card body (a sixth row style)
    // is gone. Language and provenance chips ride the badges slot.
    BookRow(
        title = result.title,
        modifier = modifier,
        coverUrl = result.coverImageUrl,
        author = result.author.takeIf { it.isNotBlank() },
        // Spec-30 T2 (#217): the resolved duration when one is known (the
        // local database or the shared metadata cache) — the search card
        // used to never show a duration at all.
        stats = result.durationSeconds?.takeIf { it > 0L }?.let { formatRowDuration(it) },
        badges = {
            // Spec-45 (#405) T7 (#495): the card's rendition language — one
            // EN/UA chip; unknown renders nothing (US3).
            if (result.language.isNotBlank()) {
                MetadataChip(language = result.language)
                Spacer(modifier = Modifier.width(AppDimens.SpaceXs))
            }
            // Spec-10 T4: which source(s) carry a book.
            result.sources.forEach { source ->
                MetadataChip(source = source.sourceName)
                Spacer(modifier = Modifier.width(AppDimens.SpaceXs))
            }
        },
        contentDescription = stringResource(R.string.a11y_open_work, result.title),
        onClick = onClick,
        testTag = "global_search_result_${result.key}"
    )
    CatalogCardStatus(result.key, actionState, onOpenBrowser)
}


@Composable
internal fun CatalogCardStatus(
    cardKey: String,
    state: CatalogCardActionState,
    onOpenBrowser: () -> Unit = {}
) {
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
            if (state.reason == CatalogCardFailure.AUDIO_REFUSED) R.string.catalog_card_audio_refused
            else if (state.action == CatalogCardAction.OPEN) R.string.catalog_card_open_error
            else R.string.catalog_card_play_error
        )
        is CatalogCardActionState.BrowserRequired -> stringResource(R.string.catalog_card_browser_required)
        is CatalogCardActionState.Cancelled -> stringResource(R.string.catalog_card_cancelled)
        else -> return
    }
    Column(modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (state is CatalogCardActionState.Failed) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (state is CatalogCardActionState.BrowserRequired) {
            TextButton(
                onClick = onOpenBrowser,
                modifier = catalogBrowserReturnFocusModifier(cardKey)
                    .testTag("catalog_card_open_browser_$cardKey")
            ) { Text(stringResource(R.string.catalog_card_open_browser)) }
        }
    }
}

@Composable
internal fun catalogBrowserReturnFocusModifier(cardKey: String): Modifier {
    val returnCardKey by CatalogBrowserFocusReturn.returnCardKey.collectAsState()
    val requester = remember(cardKey) { FocusRequester() }
    LaunchedEffect(returnCardKey) {
        if (returnCardKey != cardKey) return@LaunchedEffect
        // A nested feed action can enter composition before its focus target
        // is attached. Waiting one frame keeps the durable return token until
        // the button can actually accept focus.
        withFrameNanos { }
        if (runCatching { requester.requestFocus() }.getOrDefault(false)) {
            CatalogBrowserFocusReturn.consume(cardKey)
        }
    }
    return if (returnCardKey == cardKey) {
        // TextButton already owns the focus target. Adding a second
        // `focusable()` target here can make nested feed buttons report focus
        // on the wrapper instead of on the actual accessibility node.
        Modifier.focusRequester(requester)
    } else {
        Modifier
    }
}

