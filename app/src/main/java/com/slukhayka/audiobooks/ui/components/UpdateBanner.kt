package com.slukhayka.audiobooks.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.update.AvailableAppRelease
import com.slukhayka.audiobooks.ui.icons.PaperInkIcons
import com.slukhayka.audiobooks.ui.theme.PaperInkDimens
import com.slukhayka.audiobooks.ui.theme.PaperInkType

/**
 * Spec-36 T1 (#244) — the non-blocking «Доступна версія <version>» row.
 *
 * Never a dialog (spec-27: a rare action never ranks first) and, since the
 * «Папір і голос» migration (F5/F6/F20), never a legacy M3 `Card` with two
 * competing buttons either: it is ONE compact paper row (48 dp tall, one
 * «Оновити» action plus a 48 dp dismiss), the text comes from resources
 * (uk/en/pl) instead of hardcoded Ukrainian, and the caller places it below
 * the curated shelves rather than above the first one.
 */
@Composable
fun UpdateBanner(
    update: AvailableAppRelease,
    modifier: Modifier = Modifier,
    onDownload: () -> Unit,
    onDismiss: () -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = PaperInkDimens.SpacePage, vertical = 4.dp)
            .clip(RoundedCornerShape(PaperInkDimens.RadiusMedium))
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .heightIn(min = PaperInkDimens.TouchTarget)
            .padding(start = 14.dp, end = 2.dp)
            .testTag("app_update_banner"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PaperInkDimens.SpaceSm),
    ) {
        Icon(
            imageVector = Icons.Outlined.SystemUpdateAlt,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = stringResource(R.string.update_available_title, update.versionName),
            style = PaperInkType.RowTitle,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = onDownload,
            modifier = Modifier.testTag("app_update_download"),
        ) {
            Text(
                text = stringResource(R.string.update_available_action),
                style = PaperInkType.Label,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        IconButton(
            onClick = onDismiss,
            modifier = Modifier
                .size(PaperInkDimens.TouchTarget)
                .testTag("app_update_banner_dismiss"),
        ) {
            Icon(
                imageVector = PaperInkIcons.Close,
                contentDescription = stringResource(R.string.update_hide),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
