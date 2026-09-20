package com.slukhayka.audiobooks.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.ui.catalog.CatalogBrowserFocusReturn
import com.slukhayka.audiobooks.ui.catalog.CatalogCardAction
import com.slukhayka.audiobooks.ui.catalog.CatalogCardActionState
import com.slukhayka.audiobooks.ui.catalog.CatalogCardFailure

/**
 * v1.4 C3/C4 (ADR-0033) — the per-surface action status slot that every card
 * host renders under (or beside) its canonical card: honest action progress
 * («Перевіряємо…», the browser fallback and the terminal errors) keyed to the
 * card that owns the action.
 *
 * #567: the two named row wrappers that used to call this are gone — search
 * and the Work feed build their canonical `BookRow` directly at the call site
 * and mount this slot there.
 */
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
