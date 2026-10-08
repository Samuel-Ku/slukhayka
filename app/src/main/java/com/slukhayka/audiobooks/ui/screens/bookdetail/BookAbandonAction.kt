package com.slukhayka.audiobooks.ui.screens.bookdetail

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.BookmarkRemove
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.entries.AbandonOffer
import com.slukhayka.audiobooks.ui.theme.AppDimens

/**
 * spec-52 US28 / #1174 — «покинути книгу» in the book page's ⋮ menu, and the
 * way back from it.
 *
 * The DECISION arrives as an [AbandonOffer] (the pure `AbandonBookPolicy` owns
 * it: a finished book and a book with no listening position are never offered
 * anything). This composable only renders that answer, which is what lets both
 * edges be tested without a whole `BookDetailScreen`.
 *
 * The item keeps the 48 dp touch target of the rest of the ⋮ menu, states the
 * mark in its `stateDescription` (TalkBack hears «Книгу позначено покинутою»,
 * not just the action label), and never opens a dialog: the mark is reversible
 * from this very item, so nothing is destroyed and there is nothing to confirm
 * (ADR-0014).
 */
@Composable
internal fun BookAbandonMenuItem(
    offer: AbandonOffer,
    onAbandon: () -> Unit,
    onCancel: () -> Unit
) {
    when (offer) {
        AbandonOffer.NONE -> Unit
        AbandonOffer.ABANDON -> DropdownMenuItem(
            text = { Text(stringResource(R.string.book_detail_abandon)) },
            leadingIcon = { Icon(Icons.Default.BookmarkRemove, contentDescription = null) },
            modifier = Modifier
                .heightIn(min = AppDimens.TouchTarget)
                .testTag("book_detail_abandon"),
            onClick = onAbandon
        )

        AbandonOffer.CANCEL -> {
            val markedState = stringResource(R.string.book_detail_abandon_state_on)
            DropdownMenuItem(
                text = { Text(stringResource(R.string.book_detail_abandon_cancel)) },
                leadingIcon = {
                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null)
                },
                modifier = Modifier
                    .heightIn(min = AppDimens.TouchTarget)
                    .testTag("book_detail_abandon_cancel")
                    .semantics { stateDescription = markedState },
                onClick = onCancel
            )
        }
    }
}
