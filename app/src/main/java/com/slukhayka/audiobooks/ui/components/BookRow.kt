package com.slukhayka.audiobooks.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.ui.displayAuthor
import com.slukhayka.audiobooks.ui.theme.AppDimens

private val RowCoverSize = 64.dp

/** h:mm:ss / mm:ss — the same duration line every row slot renders. */
internal fun formatRowDuration(seconds: Long): String {
    val hrs = seconds / 3600
    val mins = (seconds % 3600) / 60
    val secs = seconds % 60
    return if (hrs > 0) {
        String.format(java.util.Locale.US, "%d:%02d:%02d", hrs, mins, secs)
    } else {
        String.format(java.util.Locale.US, "%02d:%02d", mins, secs)
    }
}

/**
 * v1.4 C3 (ADR-0033) — the ONE vertical-list row: a flat row with a 64 dp
 * cover and dividers instead of card borders (ADR-0018 wrote «divider not
 * border»; this implements it). Slots: [leading] (rank badge, avatar),
 * [badges] (inline with the title), [trailing] (▶, ⋮), [stats] (known
 * duration/chapters only — ADR-0014), [progress] hairline and [footnote]
 * (the Work-feed action status under the row — the honest action progress).
 *
 * The a11y contract rides inside: one merged row node carrying
 * [stateDescription]/[contentDescription], trailing actions as separate
 * 48 dp targets, and a trailing [HorizontalDivider] as the separator.
 */
@Composable
fun BookRow(
    title: String,
    modifier: Modifier = Modifier,
    book: AudiobookEntity? = null,
    coverUrl: String? = null,
    leading: (@Composable RowScope.() -> Unit)? = null,
    genre: String? = null,
    author: String? = null,
    stats: String? = null,
    badges: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    progress: Float? = null,
    progressTestTag: String? = null,
    stateDescription: String? = null,
    contentDescription: String? = null,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    footnote: (@Composable ColumnScope.() -> Unit)? = null,
    testTag: String? = null
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 76.dp)
                .semantics(mergeDescendants = true) {
                    if (stateDescription != null) this.stateDescription = stateDescription
                    if (contentDescription != null) this.contentDescription = contentDescription
                }
                .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
                .then(
                    when {
                        onClick != null && onLongClick != null -> Modifier.combinedClickable(
                            onClickLabel = onClickLabel,
                            onLongClickLabel = null,
                            onClick = onClick,
                            onLongClick = onLongClick
                        )
                        onClick != null -> Modifier.clickable(
                            onClickLabel = onClickLabel,
                            onClick = onClick
                        )
                        else -> Modifier
                    }
                )
                .padding(horizontal = AppDimens.PageSides, vertical = AppDimens.SpaceSm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            leading?.invoke(this)
            if (book != null) {
                BookCoverImage(
                    book = book,
                    semantics = BookCoverSemantics.Decorative,
                    modifier = Modifier
                        .size(RowCoverSize)
                        .clip(RoundedCornerShape(AppDimens.RadiusCover)),
                    contentScale = ContentScale.Crop
                )
                Spacer(modifier = Modifier.width(AppDimens.SpaceMd))
            } else if (!coverUrl.isNullOrBlank()) {
                // Catalogue rows (search, work feed) carry a cover URL, not a
                // library entity — the same 64 dp slot, artwork or placeholder.
                CatalogCoverImage(
                    coverImageUrl = coverUrl,
                    title = title,
                    semantics = BookCoverSemantics.Decorative,
                    modifier = Modifier
                        .size(RowCoverSize)
                        .clip(RoundedCornerShape(AppDimens.RadiusCover))
                )
                Spacer(modifier = Modifier.width(AppDimens.SpaceMd))
            }
            Column(modifier = Modifier.weight(1f)) {
                if (!genre.isNullOrBlank()) {
                    Text(
                        text = genre,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    badges?.invoke(this)
                }
                if (!author.isNullOrBlank()) {
                    Text(
                        text = author,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!stats.isNullOrBlank()) {
                    Text(
                        text = stats,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (progress != null && progress > 0f) {
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .padding(top = AppDimens.SpaceXs)
                            .fillMaxWidth()
                            .height(PosterProgressHairlineHeight)
                            .clip(RoundedCornerShape(AppDimens.RadiusProgress))
                            .then(
                                if (progressTestTag != null) {
                                    Modifier.testTag(progressTestTag)
                                } else {
                                    Modifier
                                }
                            ),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.outlineVariant
                    )
                }
            }
            trailing?.invoke(this)
        }
        footnote?.invoke(this)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}

/**
 * Library-entity convenience overload — the flat library-list row (the old
 * AudiobookListItem contract, preserved verbatim: the `book_item_<id>` tag,
 * the availability state description, the "4read Каталог" placeholder genre
 * skip, and the haptic-tick play action as the trailing 48 dp target).
 */
@Composable
fun BookRow(
    book: AudiobookEntity,
    onClick: () -> Unit,
    onPlayClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val availabilityState = stringResource(
        if (book.isDownloaded) R.string.a11y_available_offline
        else R.string.a11y_connection_required
    )
    val openLabel = stringResource(R.string.a11y_open_work, book.title)
    // Only the values we actually know — catalogue books start with
    // 0 chapters / 0 duration until their page is fetched (ADR-0014).
    val chaptersLabel = if (book.totalChapters > 0) {
        pluralStringResource(R.plurals.chapter_count, book.totalChapters, book.totalChapters)
    } else {
        null
    }
    val durationLabel = if (book.totalDurationSeconds > 0L) {
        formatRowDuration(book.totalDurationSeconds)
    } else {
        null
    }
    val statsLabel = when {
        chaptersLabel != null && durationLabel != null -> "$chaptersLabel • $durationLabel"
        chaptersLabel != null -> chaptersLabel
        durationLabel != null -> durationLabel
        else -> null
    }
    BookRow(
        title = book.title,
        modifier = modifier,
        book = book,
        // "4read Каталог" is the placeholder genre for catalogue books —
        // skip it so every list row isn't labelled "4read".
        genre = book.genre.takeIf {
            it.isNotBlank() && !it.contains("4read", ignoreCase = true)
        },
        author = book.displayAuthor.takeIf { it.isNotBlank() },
        stats = statsLabel,
        badges = {
            if (book.isDownloaded) {
                Spacer(modifier = Modifier.width(AppDimens.SpaceXs))
                Icon(
                    imageVector = Icons.Default.CloudDone,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(14.dp)
                )
            }
        },
        trailing = {
            IconButton(
                onClick = {
                    // Spec-22 T3: a light tick on playback start.
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onPlayClick()
                },
                modifier = Modifier
                    .size(AppDimens.TouchTarget)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = stringResource(R.string.a11y_play_work, book.title),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
        },
        stateDescription = availabilityState,
        onClick = onClick,
        onClickLabel = openLabel,
        testTag = "book_item_${book.id}"
    )
}
