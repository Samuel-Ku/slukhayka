package com.slukhayka.audiobooks.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.ui.theme.AppDimens

/**
 * v1.4 E2 — the ONE browser-door row, both doors (Sluhay debug + 4read).
 * Ruling (spec's snapshot criterion + ADR-0033): a flat [BookRow], not a
 * raised Card — «divider not border» (ADR-0018), and the doors sit directly
 * above/below the work-feed BookRows, so one vocabulary rules the whole
 * vertical rhythm. Same API as the former card version: [text] defaults to
 * «Більше книг на $displayName»; the 4read search doors (#440) pass a
 * custom prompt.
 *
 * A11y: one merged clickable row node (BookRow semantics); both icons are
 * decorative (null descriptions) — the row's label is the prompt text.
 */
@Composable
fun OpenWebSourceRow(
    displayName: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    text: String = stringResource(R.string.home_more_books_on, displayName),
    testTag: String = "open_web_source_${displayName.lowercase()}",
) {
    BookRow(
        title = text,
        modifier = modifier,
        onClick = onClick,
        testTag = testTag,
        leading = {
            Icon(
                imageVector = Icons.Default.Language,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(AppDimens.TouchTarget)
                    .padding(14.dp)
            )
        },
        trailing = {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
    )
}
