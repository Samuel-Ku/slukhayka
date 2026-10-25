package com.slukhayka.audiobooks.ui.screens.bookdetail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.imports.LocalAccessRecovery
import com.slukhayka.audiobooks.ui.theme.AppDimens

/**
 * #1230 — the local-access recovery surface of the book page.
 *
 * It renders exactly [LocalAccessRecovery.State.actions]; the order comes
 * from the shared pure seam, so this composable cannot quietly put the
 * network first. File and folder re-picks are the first doors, the known
 * folder may be re-scanned, and the Source Watch (the network follow-up)
 * is always last and explicitly labelled as a later action.
 */
@Composable
fun LocalAccessRecoveryPanel(
    state: LocalAccessRecovery.State,
    bookWatched: Boolean,
    onRecoverFile: () -> Unit,
    onRecoverFolder: () -> Unit,
    onRescanKnownFolder: () -> Unit,
    onWatch: () -> Unit,
    onStopWatching: () -> Unit,
    modifier: Modifier = Modifier
) {
    val title = stringResource(R.string.book_detail_local_access_unavailable)
    Surface(
        shape = RoundedCornerShape(AppDimens.RadiusPanel),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .semantics { contentDescription = title }
            .testTag("book_detail_local_access_recovery")
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            state.actions().forEach { action ->
                when (action) {
                    LocalAccessRecovery.Action.RESELECT_FILE -> TextButton(
                        onClick = onRecoverFile,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("book_detail_recover_file")
                    ) {
                        Text(stringResource(R.string.book_detail_recover_file))
                    }
                    LocalAccessRecovery.Action.RESELECT_FOLDER -> TextButton(
                        onClick = onRecoverFolder,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("book_detail_recover_folder")
                    ) {
                        Text(stringResource(R.string.book_detail_recover_folder))
                    }
                    LocalAccessRecovery.Action.RESCAN_KNOWN_FOLDER -> TextButton(
                        onClick = onRescanKnownFolder,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("book_detail_recover_rescan")
                    ) {
                        Text(stringResource(R.string.book_detail_recover_rescan))
                    }
                    LocalAccessRecovery.Action.SOURCE_WATCH -> {
                        Text(
                            text = stringResource(R.string.book_detail_recover_network_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        if (bookWatched) {
                            TextButton(
                                onClick = onStopWatching,
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("book_detail_watch_stop")
                            ) {
                                Text(stringResource(R.string.book_detail_watching))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.book_detail_stop_watching))
                            }
                        } else {
                            TextButton(
                                onClick = onWatch,
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("book_detail_watch_source")
                            ) {
                                Text(stringResource(R.string.book_detail_watch_action))
                            }
                        }
                    }
                }
            }
        }
    }
}
