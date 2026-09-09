package com.slukhayka.audiobooks.ui.screens.bookdetail

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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

// #382: найдовший реальний лейбл кнопки — «Продовжити з HH:MM:SS»; такі лейбли
// не влазять в один рядок дій без розриву посередині слова («Продовж/ити»).
private const val PLAY_LABEL_ROW_LIMIT = 12

internal enum class BookDetailDownloadAction {
    Start,
    Cancel,
    Continue,
    Remove
}

internal fun bookDetailDownloadAction(
    isDownloading: Boolean,
    isPaused: Boolean,
    isDownloaded: Boolean
): BookDetailDownloadAction = when {
    isDownloading -> BookDetailDownloadAction.Cancel
    isPaused -> BookDetailDownloadAction.Continue
    isDownloaded -> BookDetailDownloadAction.Remove
    else -> BookDetailDownloadAction.Start
}

/**
 * Primary book actions reflow into a vertical stack at accessibility font
 * scale or when the play label alone is too long for one row (#382). The
 * visible and semantic controls are the same in both layouts;
 * nothing is hidden behind a TalkBack-only branch.
 */
@Composable
internal fun BookDetailPrimaryActions(
    workTitle: String,
    playLabel: String,
    streamOnly: Boolean,
    downloadAction: BookDetailDownloadAction,
    downloadProgress: Float,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
    onAddBookmark: () -> Unit,
    modifier: Modifier = Modifier
) {
    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    val progressPercent = (downloadProgress.coerceIn(0f, 1f) * 100).toInt()
    val downloadActionDescription = when (downloadAction) {
        BookDetailDownloadAction.Cancel -> stringResource(
            R.string.book_detail_download_in_progress,
            workTitle,
            progressPercent
        )
        BookDetailDownloadAction.Continue -> stringResource(
            R.string.book_detail_download_continue,
            workTitle,
            progressPercent
        )
        BookDetailDownloadAction.Remove -> stringResource(
            R.string.book_detail_download_remove,
            workTitle
        )
        BookDetailDownloadAction.Start -> stringResource(
            R.string.book_detail_download_add,
            workTitle
        )
    }
    val downloadState = stringResource(
        when (downloadAction) {
            BookDetailDownloadAction.Cancel -> R.string.book_detail_downloading
            BookDetailDownloadAction.Continue -> R.string.book_detail_download_paused
            BookDetailDownloadAction.Remove -> R.string.book_detail_downloaded
            BookDetailDownloadAction.Start -> R.string.book_detail_streaming
        }
    )
    val playAction = stringResource(R.string.book_detail_play_action, playLabel, workTitle)
    val bookmarkAction = stringResource(R.string.book_detail_add_bookmark, workTitle)

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        // #382: довгий лейбл сам по собі привід складати дії вертикально —
        // у рядку «Продовжити з HH:MM:SS» слову бракує місця навіть із
        // обтягнутим contentPadding. Короткі («Слухати», «Пауза») далі живуть
        // в одному рядку.
        val playLabelIsLong = playLabel.length > PLAY_LABEL_ROW_LIMIT
        val stackActions = fontScale >= 1.5f || playLabelIsLong || maxWidth < 340.dp
        if (stackActions) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BookDetailPlayButton(
                    label = playLabel,
                    actionDescription = playAction,
                    onClick = onPlay,
                    modifier = Modifier.fillMaxWidth()
                )
                if (!streamOnly) {
                    BookDetailDownloadButton(
                        action = downloadAction,
                        downloadProgress = downloadProgress,
                        actionDescription = downloadActionDescription,
                        state = downloadState,
                        onClick = onDownload,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                BookDetailBookmarkButton(
                    actionDescription = bookmarkAction,
                    onClick = onAddBookmark,
                    showLabel = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BookDetailPlayButton(
                    label = playLabel,
                    actionDescription = playAction,
                    onClick = onPlay,
                    modifier = Modifier.weight(if (streamOnly) 1f else 1.2f)
                )
                if (!streamOnly) {
                    BookDetailDownloadButton(
                        action = downloadAction,
                        downloadProgress = downloadProgress,
                        actionDescription = downloadActionDescription,
                        state = downloadState,
                        onClick = onDownload,
                        modifier = Modifier.weight(1f)
                    )
                }
                BookDetailBookmarkButton(
                    actionDescription = bookmarkAction,
                    onClick = onAddBookmark,
                    showLabel = false
                )
            }
        }
    }
}

@Composable
private fun BookDetailPlayButton(
    label: String,
    actionDescription: String,
    onClick: () -> Unit,
    modifier: Modifier
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        shape = RoundedCornerShape(AppDimens.RadiusPanel),
        // #382: дефолтні 24dp по горизонталі плюс іконка з'їдали ширину слова
        // «Продовжити»; паддінг однаковий з download-кнопкою, підлога ширини
        // тримає найдовший лейбл на вузьких панелях.
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
        modifier = modifier
            .widthIn(min = 150.dp)
            .heightIn(min = 50.dp)
            .testTag("play_book_button")
            .semantics { contentDescription = actionDescription }
    ) {
        Icon(
            imageVector = Icons.Default.PlayArrow,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun BookDetailDownloadButton(
    action: BookDetailDownloadAction,
    downloadProgress: Float,
    actionDescription: String,
    state: String,
    onClick: () -> Unit,
    modifier: Modifier
) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(AppDimens.RadiusPanel),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (action == BookDetailDownloadAction.Remove) MaterialTheme.colorScheme.secondary
            else MaterialTheme.colorScheme.outlineVariant
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = if (action == BookDetailDownloadAction.Remove) MaterialTheme.colorScheme.secondary
            else MaterialTheme.colorScheme.onSurface
        ),
        // #382: спільний паддінг із play-кнопкою — жодних per-button налаштувань.
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
        modifier = modifier
            .heightIn(min = 50.dp)
            .testTag("download_offline_button")
            .semantics {
                contentDescription = actionDescription
                stateDescription = state
            }
    ) {
        if (action == BookDetailDownloadAction.Cancel) {
            CircularProgressIndicator(
                progress = { downloadProgress.coerceIn(0.05f, 0.95f) },
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                stringResource(
                    R.string.book_detail_cancel_progress_short,
                    (downloadProgress.coerceIn(0f, 1f) * 100).toInt()
                )
            )
        } else {
            Text(
                text = stringResource(
                    when (action) {
                        BookDetailDownloadAction.Continue -> R.string.book_detail_continue_short
                        BookDetailDownloadAction.Remove -> R.string.book_detail_offline_short
                        BookDetailDownloadAction.Start -> R.string.book_detail_download_short
                        BookDetailDownloadAction.Cancel -> error("handled above")
                    }
                ),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = if (action == BookDetailDownloadAction.Remove) {
                    Icons.Default.CloudDone
                } else {
                    Icons.Default.CloudDownload
                },
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun BookDetailBookmarkButton(
    actionDescription: String,
    onClick: () -> Unit,
    showLabel: Boolean,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(AppDimens.RadiusPanel),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            .heightIn(min = 50.dp)
            .testTag("bookmark_button")
            .semantics { contentDescription = actionDescription }
    ) {
        Icon(
            imageVector = Icons.Default.BookmarkAdd,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        if (showLabel) {
            Spacer(modifier = Modifier.width(6.dp))
            Text(stringResource(R.string.book_detail_add_bookmark_short), maxLines = 2)
        }
    }
}

