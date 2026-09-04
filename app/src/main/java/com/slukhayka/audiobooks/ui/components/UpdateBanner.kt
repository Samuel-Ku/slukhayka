package com.slukhayka.audiobooks.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import com.slukhayka.audiobooks.R
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.data.update.AvailableAppRelease

/**
 * Spec-36 T1 (#244) — the non-blocking «Доступна Слухайка v<версія>» banner
 * at the top of Огляд. Never a dialog (spec-27: a rare action never ranks
 * first); the download hands off to the browser on the release's direct apk
 * link, the dismiss hides the row (the dismissal lifecycle is T2, #245).
 */
@Composable
fun UpdateBanner(
    update: AvailableAppRelease,
    modifier: Modifier = Modifier,
    onDownload: () -> Unit,
    onDismiss: () -> Unit
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Доступна Слухайка v${update.versionName}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Оновлення застосунку",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.update_hide))
            }
            TextButton(onClick = onDownload) {
                Text(stringResource(R.string.book_detail_download_short))
            }
        }
    }
}
