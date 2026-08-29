package com.slukhayka.audiobooks.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.ui.displayAuthor
import com.slukhayka.audiobooks.ui.theme.AppDimens

internal val CompactBookDismissVisualSize = 18.dp
private val CompactBookDismissIconSize = 12.dp
private val CompactBookDismissInset = 2.dp

/**
 * ADR-0018 — CompactBookCard: the horizontal-shelf poster (portrait cover,
 * title and author, no default play triangle). Tapping opens the book page.
 *
 * The optional [onNotInterested] adds the Listen-shelf «Не цікаво» dismiss ✕
 * over the cover (wayfinder #62, reversible) — when absent the card keeps its
 * canonical shape unchanged.
 *
 * US-3 (spec-28 #199): the optional [progress] (0..1) draws a thin progress
 * hairline along the cover's BOTTOM edge for a STARTED book; an unstarted
 * book (null or 0 progress) keeps a clean cover. The shelf feeds the real
 * listening percent; unknown duration (percent 0) honestly shows no line.
 */
@Composable
fun CompactBookCard(
    book: AudiobookEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onNotInterested: (() -> Unit)? = null,
    progress: Float? = null,
    // spec-28 (#201): an optional context caption above the title (e.g. the
    // «Частина N» line of the «Далі у серії» shelf). Absent for every other
    // shelf — the canonical card shape stays unchanged.
    caption: String? = null
) {
    val progressDescription = progress
        ?.takeIf { it > 0f }
        ?.let { stringResource(R.string.a11y_listened_percent, (it.coerceIn(0f, 1f) * 100).toInt()) }
    // Local imports are playable from their SAF/file source even when the
    // legacy downloaded projection is false. A blank sourceUrl is their
    // canonical domain identity; only remote, non-downloaded works need data.
    val availabilityDescription = if (book.isDownloaded || book.sourceUrl.isBlank()) {
        stringResource(R.string.a11y_available_offline)
    } else {
        stringResource(R.string.a11y_connection_required)
    }
    val workState = listOfNotNull(progressDescription, availabilityDescription).joinToString(". ")

    Box(modifier = modifier.width(120.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    if (workState.isNotBlank()) stateDescription = workState
                }
                .clickable(role = Role.Button, onClick = onClick)
                .testTag("compact_book_${book.id}"),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box {
                BookCoverImage(
                    book = book,
                    semantics = BookCoverSemantics.Decorative,
                    modifier = Modifier
                        .width(120.dp)
                        .height(168.dp)
                        .clip(RoundedCornerShape(AppDimens.RadiusCardLg))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(AppDimens.RadiusCardLg))
                )
                // US-3: the thin progress hairline along the cover's bottom edge,
                // clipped to the cover's rounded shape and filled to the consumed
                // fraction. Only a STARTED book draws it — an unstarted or
                // unknown-duration book keeps a clean cover (ADR-0014 honest data).
                if (progress != null && progress > 0f) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(RoundedCornerShape(AppDimens.RadiusCardLg))
                            .testTag("compact_book_progress_${book.id}")
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progress.coerceIn(0f, 1f))
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            caption?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(2.dp))
            }
            Text(
                text = book.title,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            if (book.displayAuthor.isNotBlank()) {
                Text(
                    text = book.displayAuthor,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        if (onNotInterested != null) {
            // #372: the 48 dp touch target stays at the top-right corner, while
            // the compact «Не цікаво» indicator does not dominate the cover.
            IconButton(
                onClick = onNotInterested,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = CompactBookDismissInset, top = CompactBookDismissInset)
                    .size(AppDimens.TouchTarget)
                    .testTag("not_interested_${book.id}")
            ) {
                Box(
                    modifier = Modifier
                        .size(CompactBookDismissVisualSize)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f))
                        .testTag("not_interested_visual_${book.id}"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.a11y_not_interested_work, book.title),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(CompactBookDismissIconSize)
                    )
                }
            }
        }
    }
}
