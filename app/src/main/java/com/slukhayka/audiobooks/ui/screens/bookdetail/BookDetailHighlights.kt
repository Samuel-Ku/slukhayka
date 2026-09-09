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

/**
 * #40 decision 1 — the book page's favourite toggle: a filled heart when the
 * book is in «Улюблені», an outlined one otherwise. Public so the snapshot
 * seam can pin both states and the toggle from fixture data.
 */
@Composable
fun FavoriteButton(
    isFavorite: Boolean,
    onToggle: () -> Unit,
    bookTitle: String = ""
) {
    val contextualTitle = bookTitle.takeIf(String::isNotBlank) ?: "книгу"
    val actionDescription = stringResource(
        if (isFavorite) R.string.book_detail_favorite_remove else R.string.book_detail_favorite_add,
        contextualTitle
    )
    val currentState = stringResource(
        if (isFavorite) R.string.book_detail_favorite_on else R.string.book_detail_favorite_off
    )
    IconButton(
        onClick = onToggle,
        modifier = Modifier
            .testTag("favorite_toggle_button")
            .semantics {
                contentDescription = actionDescription
                stateDescription = currentState
            }
    ) {
        Icon(
            imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
            contentDescription = null,
            tint = if (isFavorite) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * #40 decision 1 — the book's series as a tappable pill on the book page:
 * "«Чаклун» • Кн. 2". Opens the series catalogue page (spec-9 T1
 * SeriesScreen), which holds the volume order and the next-unread-volume
 * CTA. Public (not private) so the snapshot seam can pin the pill's
 * presence and tap through it with fixture data.
 */
@Composable
fun SeriesPill(
    seriesTitle: String,
    seriesIndex: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        shape = RoundedCornerShape(AppDimens.RadiusCard),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
        ),
        modifier = modifier
            .testTag("book_detail_series_pill")
            .defaultMinSize(minHeight = 48.dp)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = AppDimens.SpaceMd, vertical = AppDimens.SpaceSm),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (seriesIndex > 0) "«$seriesTitle» • Кн. $seriesIndex" else "«$seriesTitle»",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Spec-25 (#171) — the book page's universe line under the series pill:
 * «Всесвіт: «Перший закон»». Renders only for a resolved universe — a
 * missing one never degrades the book page. Public (not private) so the
 * snapshot seam can pin the line with fixture data.
 */
@Composable
fun BookUniverseLine(universeName: String) {
    Text(
        text = "Всесвіт: «$universeName»",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.testTag("book_detail_universe_line")
    )
}

