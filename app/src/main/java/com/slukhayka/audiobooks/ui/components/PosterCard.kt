package com.slukhayka.audiobooks.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.source.GlobalSearchResult
import com.slukhayka.audiobooks.ui.displayAuthor
import com.slukhayka.audiobooks.ui.theme.AppBadgeScrim
import com.slukhayka.audiobooks.ui.theme.AppBadgeScrimBorder
import com.slukhayka.audiobooks.ui.theme.AppDimens

/** v1.4 C2 (ADR-0033): the one poster is 120×168; the one cycle is 132×78. */
internal val PosterWidth = 120.dp
internal val PosterHeight = 168.dp
internal val PosterProgressHairlineHeight = 3.dp
internal val PosterDismissVisualSize = 18.dp
private val CycleWidth = 132.dp
private val CycleHeight = 78.dp

/**
 * v1.4 C2 (ADR-0033) — the ONE portrait poster (120×168), the canonical
 * horizontal-shelf card. Every element is a slot: title, author, duration,
 * progress hairline, caption, dismiss («Не цікаво»), download. Surfaces
 * render only what they really know (ADR-0014) — a null slot is absent,
 * never a placeholder.
 *
 * The a11y contract rides inside: one merged clickable node, the optional
 * [stateDescription] on the card, dismiss/download as separate 48 dp
 * targets, and an optional [actionHost] under the poster for the surface's
 * catalogue-action status ([com.slukhayka.audiobooks.ui.screens.CatalogCardStatus]).
 */
@Composable
fun PosterCard(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    coverUrl: String? = null,
    genre: String? = null,
    author: String? = null,
    duration: String? = null,
    caption: String? = null,
    progress: Float? = null,
    progressTestTag: String? = null,
    stateDescription: String? = null,
    overlayDescription: String? = null,
    dismissContentDescription: String? = null,
    onDismiss: (() -> Unit)? = null,
    dismissTestTag: String? = null,
    downloadIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    downloadContentDescription: String? = null,
    onDownload: (() -> Unit)? = null,
    downloadProgress: Float? = null,
    downloadTestTag: String? = null,
    overlay: (@Composable androidx.compose.foundation.layout.BoxScope.() -> Unit)? = null,
    preflightKey: Any? = null,
    onPreflight: (() -> Unit)? = null,
    actionHost: (@Composable () -> Unit)? = null,
    testTag: String? = null,
    onClickLabel: String? = null
) {
    if (onPreflight != null) {
        LaunchedEffect(preflightKey) { onPreflight() }
    }
    Column(modifier = modifier.width(PosterWidth)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    if (stateDescription != null) this.stateDescription = stateDescription
                    if (overlayDescription != null) this.contentDescription = overlayDescription
                }
                .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
                .clickable(
                    role = Role.Button,
                    // The #557 contract rides inside the canonical card:
                    // TalkBack announces «Відкрити книгу: <назва>» unless a
                    // surface overrides the label.
                    onClickLabel = onClickLabel
                        ?: stringResource(R.string.a11y_open_work, title),
                    onClick = onClick
                ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box {
                CatalogCoverImage(
                    coverImageUrl = coverUrl,
                    title = title,
                    semantics = BookCoverSemantics.Decorative,
                    genre = genre,
                    modifier = Modifier
                        .width(PosterWidth)
                        .height(PosterHeight)
                        .clip(RoundedCornerShape(AppDimens.RadiusCardLg))
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant,
                            RoundedCornerShape(AppDimens.RadiusCardLg)
                        )
                )
                if (progress != null && progress > 0f) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(PosterProgressHairlineHeight)
                            .clip(RoundedCornerShape(AppDimens.RadiusCardLg))
                            .then(if (progressTestTag != null) Modifier.testTag(progressTestTag) else Modifier)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progress.coerceIn(0f, 1f))
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
                if (onDownload != null && downloadIcon != null) {
                    val downloading = downloadProgress
                    if (downloading != null) {
                        // Downloading: a thin track along the cover's bottom edge,
                        // filled to the real fraction — recomposes per chapter.
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
                                    .fillMaxWidth(downloading.coerceIn(0f, 1f))
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
                                .background(AppBadgeScrim)
                                .border(1.dp, AppBadgeScrimBorder, RoundedCornerShape(AppDimens.RadiusXs))
                                .then(
                                    if (downloadTestTag != null) {
                                        Modifier.testTag(downloadTestTag)
                                    } else {
                                        Modifier
                                    }
                                )
                        ) {
                            Icon(
                                imageVector = downloadIcon,
                                contentDescription = downloadContentDescription,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
                if (overlay != null) overlay()
                if (onDismiss != null && dismissContentDescription != null) {
                    // #372: the 48 dp touch target stays at the top-right corner
                    // while the visible «Не цікаво» indicator does not dominate
                    // the cover.
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(2.dp)
                            .size(AppDimens.TouchTarget)
                            .then(if (dismissTestTag != null) Modifier.testTag(dismissTestTag) else Modifier)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(PosterDismissVisualSize)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f))
                                .then(
                                    if (dismissTestTag != null) {
                                        // Preserves the pinned #372 contract:
                                        // not_interested_visual_<id>
                                        Modifier.testTag(
                                            dismissTestTag.replaceFirst(
                                                "not_interested_",
                                                "not_interested_visual_"
                                            )
                                        )
                                    } else {
                                        Modifier
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = dismissContentDescription,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(12.dp)
                            )
                        }
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
                text = title,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            if (!author.isNullOrBlank()) {
                Text(
                    text = author,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (duration != null) {
                Text(
                    text = duration,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        actionHost?.invoke()
    }
}

/**
 * Library-entity convenience overload — the Listen shelves' shape (the old
 * CompactBookCard contract, preserved verbatim: test tags, state description,
 * dismiss indicator sizing).
 */
@Composable
fun PosterCard(
    book: AudiobookEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onNotInterested: (() -> Unit)? = null,
    progress: Float? = null,
    caption: String? = null
) {
    val progressDescription = progress
        ?.takeIf { it > 0f }
        ?.let { stringResource(R.string.a11y_listened_percent, (it.coerceIn(0f, 1f) * 100).toInt()) }
    // Local imports are playable from their SAF/file source even when the
    // legacy downloaded projection is false; a blank sourceUrl is their
    // canonical domain identity.
    val availabilityDescription = if (book.isDownloaded || book.sourceUrl.isBlank()) {
        stringResource(R.string.a11y_available_offline)
    } else {
        stringResource(R.string.a11y_connection_required)
    }
    val workState = listOfNotNull(progressDescription, availabilityDescription).joinToString(". ")
    PosterCard(
        title = book.title,
        coverUrl = book.coverImageUrl,
        author = book.displayAuthor.takeIf { it.isNotBlank() },
        onClick = onClick,
        modifier = modifier,
        caption = caption,
        progress = progress,
        progressTestTag = "compact_book_progress_${book.id}",
        stateDescription = workState,
        dismissContentDescription = onNotInterested?.let {
            stringResource(R.string.a11y_not_interested_work, book.title)
        },
        onDismiss = onNotInterested,
        dismissTestTag = onNotInterested?.let { "not_interested_${book.id}" },
        testTag = "compact_book_${book.id}"
    )
}

/**
 * Catalogue-Work convenience overload — the Огляд cross-source rails' shape
 * (the old UnifiedCatalogCard contract: one-tap download slot, per-source
 * provenance stays in the row model).
 */
@Composable
fun PosterCard(
    result: GlobalSearchResult,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onDownload: (() -> Unit)? = null,
    downloadAllowed: Boolean = true,
    downloadProgress: Float? = null,
    isDownloaded: Boolean = false,
    preflightKey: Any? = null,
    onPreflight: (() -> Unit)? = null,
    actionHost: (@Composable () -> Unit)? = null,
    // The old UnifiedCatalogCard contract keeps its tag by default; the
    // collections rail overrides it (same old contract).
    testTag: String? = "unified_catalog_${result.key}"
) {
    PosterCard(
        title = result.title,
        coverUrl = result.coverImageUrl,
        author = result.author.takeIf { it.isNotBlank() },
        onClick = onClick,
        modifier = modifier,
        preflightKey = preflightKey,
        onPreflight = onPreflight,
        actionHost = actionHost,
        testTag = testTag,
        overlayDescription = stringResource(R.string.a11y_open_work, result.title),
        downloadIcon = if (onDownload != null && downloadAllowed) {
            if (isDownloaded) Icons.Default.CloudDone else Icons.Default.CloudDownload
        } else {
            null
        },
        downloadContentDescription = stringResource(
            if (isDownloaded) R.string.a11y_downloaded_work else R.string.a11y_download_work,
            result.title
        ),
        onDownload = if (downloadAllowed) onDownload else null,
        downloadProgress = downloadProgress,
        downloadTestTag = "unified_catalog_download_${result.key}"
    )
}

/**
 * v1.4 C2 (ADR-0033) — the ONE landscape cycle card (132×78): a series is
 * one object type everywhere. The [subtitle] slot carries either the honest
 * progress line («Прослухано X із Y») or the engine's reason chip
 * («схоже на X») — [subtitleIsReason] switches the chip presentation.
 */
@Composable
fun CycleCard(
    title: String,
    coverUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onClickLabel: String? = null,
    subtitle: String? = null,
    subtitleIsReason: Boolean = false,
    testTag: String? = null
) {
    Column(
        modifier = modifier
            .width(CycleWidth)
            .clickable(
                // Same #557 contract as the poster: «Відкрити серію: <назва>».
                onClickLabel = onClickLabel
                    ?: stringResource(R.string.a11y_open_series, title),
                onClick = onClick
            )
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CatalogCoverImage(
            coverImageUrl = coverUrl,
            title = title,
            semantics = BookCoverSemantics.Decorative,
            modifier = Modifier
                .width(CycleWidth)
                .height(CycleHeight)
                .clip(RoundedCornerShape(AppDimens.RadiusCard))
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant,
                    RoundedCornerShape(AppDimens.RadiusCard)
                )
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.primary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        if (subtitle != null) {
            Spacer(modifier = Modifier.height(4.dp))
            if (subtitleIsReason) {
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                            .fillMaxWidth()
                    )
                }
            } else {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
