package com.slukhayka.audiobooks.ui.screens.bookdetail

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slukhayka.audiobooks.data.catalog.CatalogBook
import com.slukhayka.audiobooks.data.catalog.CatalogPerson
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.BookmarkEntity
import com.slukhayka.audiobooks.data.db.ChapterEntity
import com.slukhayka.audiobooks.data.db.PersonBookmarkKey
import com.slukhayka.audiobooks.data.db.DownloadState
import com.slukhayka.audiobooks.data.db.PersonRole
import com.slukhayka.audiobooks.data.downloads.OfflineDownloads
import com.slukhayka.audiobooks.data.entries.LibraryEntries
import com.slukhayka.audiobooks.data.listening.ListeningStateStore
import com.slukhayka.audiobooks.data.personbookmarks.PersonBookmarks
import com.slukhayka.audiobooks.data.source.sourceDisplayName
import com.slukhayka.audiobooks.data.source.sourceIdForUrl
import com.slukhayka.audiobooks.data.source.streamOnlyFor
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.ui.library.siblingNarrations
import com.slukhayka.audiobooks.ui.MainViewModel
import com.slukhayka.audiobooks.ui.ReviewSaveResult
import com.slukhayka.audiobooks.ui.bookPersonPath
import com.slukhayka.audiobooks.ui.reviewWorkIdFor
import com.slukhayka.audiobooks.ui.components.BookmarkDialog
import com.slukhayka.audiobooks.ui.components.BookCoverImage
import com.slukhayka.audiobooks.ui.components.BookCoverSemantics
import com.slukhayka.audiobooks.ui.components.AppSectionHeader
import com.slukhayka.audiobooks.ui.components.MetadataChip
import com.slukhayka.audiobooks.ui.components.PosterCard
import com.slukhayka.audiobooks.ui.components.RestoreFocusAfterModal
import com.slukhayka.audiobooks.ui.components.accessibilityModalBackground
import com.slukhayka.audiobooks.ui.components.accessibilityPane
import com.slukhayka.audiobooks.ui.displayAuthor
import com.slukhayka.audiobooks.ui.displayNarrator
import com.slukhayka.audiobooks.ui.library.BookPlayState
import com.slukhayka.audiobooks.ui.library.bookPlayLabel
import com.slukhayka.audiobooks.ui.library.bookPlayState
import com.slukhayka.audiobooks.ui.library.bookPositionAndTotal
import com.slukhayka.audiobooks.ui.library.ukPlural
import com.slukhayka.audiobooks.ui.theme.*

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChapterRowItem(
    chapter: ChapterEntity,
    index: Int,
    isCurrent: Boolean,
    isPlaying: Boolean,
    focusRequester: FocusRequester? = null,
    onPlayClick: () -> Unit,
    onPauseClick: () -> Unit,
    // #397 — the chapter's Source Track copy is on disk; the listener may
    // delete just this chapter's copy.
    isDownloadedCopy: Boolean = false,
    onDeleteCopy: (() -> Unit)? = null,
    // #396 — selection mode: long-press enters it, picking toggles a chapter.
    selectionMode: Boolean = false,
    isSelected: Boolean = false,
    selectable: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onToggleSelect: (() -> Unit)? = null
) {
    val duration = chapter.durationSeconds.takeIf { it > 0L }?.let(MainViewModel::formatTime)
    val chapterSummary = if (duration != null) {
        stringResource(R.string.book_detail_chapter_summary, chapter.title, duration)
    } else {
        stringResource(R.string.book_detail_chapter_summary_unknown, chapter.title)
    }
    val chapterState = stringResource(
        when {
            isPlaying -> R.string.book_detail_chapter_playing
            isCurrent -> R.string.book_detail_chapter_paused
            else -> R.string.book_detail_chapter_not_current
        }
    )
    val actionLabel = when {
        selectionMode && selectable -> stringResource(
            if (isSelected) R.string.book_detail_chapter_deselect else R.string.book_detail_chapter_select,
            chapter.title
        )
        isPlaying -> stringResource(R.string.book_detail_chapter_pause, chapter.title)
        isCurrent -> stringResource(R.string.book_detail_chapter_resume, chapter.title)
        else -> stringResource(R.string.book_detail_chapter_play, chapter.title)
    }
    val onAction: () -> Unit = when {
        selectionMode && selectable -> onToggleSelect ?: onPlayClick
        isPlaying -> onPauseClick
        else -> onPlayClick
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(AppDimens.RadiusCard))
            .border(
                1.dp,
                if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(AppDimens.RadiusCard)
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
            else MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // One stable node owns touch, semantics and focus. Splitting
                // these responsibilities between Card and Row exposes two
                // clickable accessibility nodes with identical bounds.
                .testTag("book_detail_chapter_${chapter.id}")
                .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
                // A modal must return to this one node even in Touch mode;
                // clickable's SystemDefined focusability otherwise rejects it.
                .focusProperties { canFocus = true }
                .combinedClickable(onClickLabel = actionLabel, onClick = onAction, onLongClick = onLongClick)
                .semantics(mergeDescendants = true) {
                    contentDescription = chapterSummary
                    stateDescription = chapterState
                    selected = isCurrent
                }
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (isCurrent) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
                    } else {
                        Text(
                            text = "${index + 1}",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = chapter.title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium
                    ),
                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (chapter.durationSeconds > 0L) {
                    // Spec-27 (#204): the duration renders without the
                    // «Duration:» label (US-15, P1 #8).
                    Text(
                        text = MainViewModel.formatTime(chapter.durationSeconds),
                        // Spec-22 T2: tabular figures for duration counters.
                        style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (selectionMode) {
                // #396 — already-downloaded chapters are marked and not
                // selectable; the rest carry a checkbox.
                if (isDownloadedCopy) {
                    Icon(
                        imageVector = Icons.Default.CloudDone,
                        contentDescription = stringResource(
                            R.string.book_detail_chapter_already_downloaded,
                            chapter.title
                        ),
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onToggleSelect?.invoke() },
                        modifier = Modifier.testTag("chapter_select_${chapter.id}")
                    )
                }
            } else {
                if (isDownloadedCopy && onDeleteCopy != null) {
                    IconButton(onClick = onDeleteCopy) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = stringResource(R.string.book_detail_chapter_delete_copy),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Icon(
                    imageVector = if (isPlaying) Icons.Default.PauseCircle else Icons.Default.PlayCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}

@Composable
fun BookmarkRowItem(
    bookmark: BookmarkEntity,
    workTitle: String,
    onJumpClick: () -> Unit,
    onDeleteClick: () -> Unit,
    deleteFocusRequester: FocusRequester? = null
) {
    val timestamp = MainViewModel.formatTime(bookmark.timestampSeconds)
    val jumpLabel = stringResource(
        R.string.book_detail_bookmark_jump,
        workTitle,
        bookmark.chapterTitle,
        timestamp
    )
    val deleteLabel = stringResource(
        R.string.book_detail_bookmark_delete,
        workTitle,
        bookmark.chapterTitle,
        timestamp
    )
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(AppDimens.RadiusCard))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(AppDimens.RadiusCard)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Bookmark,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = bookmark.chapterTitle,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "На ${MainViewModel.formatTime(bookmark.timestampSeconds)}: ${bookmark.note}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(
                onClick = onJumpClick,
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = jumpLabel,
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            IconButton(
                onClick = onDeleteClick,
                modifier = Modifier
                    .then(
                        if (deleteFocusRequester != null) {
                            Modifier.focusRequester(deleteFocusRequester)
                        } else Modifier
                    )
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = deleteLabel,
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

