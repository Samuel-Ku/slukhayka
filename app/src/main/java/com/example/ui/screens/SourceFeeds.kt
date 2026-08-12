package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.catalog.CatalogBook
import com.example.data.repository.AudiobookRepository.SourceNewFeed
import com.example.data.source.SourceBook
import com.example.ui.components.AppSectionHeader
import com.example.ui.theme.AppDimens

/**
 * Spec-10 T5 — one «Нове з <джерела>» feed row: the section header plus a
 * horizontal row of book cards, same shape as the existing «Нове на 4read»
 * rows. Extracted as a pure `@Composable` (no ViewModel) so the snapshot seam
 * can pin the row from fixture data.
 *
 * Spec-13 T4 — a session-bound source (WebView pattern) with no live session
 * renders the [StaleSessionRow] CTA instead of the book row: the feed is
 * hydrated through the user's browser session, so an absent/stale session
 * must not show dead data.
 */
@Composable
fun SourceFeedRow(
    feed: SourceNewFeed,
    onBookClick: (SourceBook) -> Unit,
    onOpenWebSource: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    if (feed.sessionBound && feed.books.isEmpty()) {
        StaleSessionRow(
            sourceName = feed.sourceName,
            onOpen = onOpenWebSource,
            modifier = modifier
        )
        return
    }
    Column(modifier = modifier) {
        AppSectionHeader(title = "Нове з ${feed.sourceName}")
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(feed.books, key = { it.url }) { book ->
                CatalogBookCard(
                    book = sourceBookToCatalogBook(feed.sourceId, book),
                    onClick = { onBookClick(book) }
                )
            }
        }
    }
}

/**
 * Spec-13 T4 — the stale/absent-session state of a WebView-source feed row:
 * no live Cloudflare session means no hydrated data. The CTA re-opens the
 * source's browser surface (which passes the challenge and refreshes the
 * session); the next [com.example.ui.MainViewModel.closeWebSource] re-hydrates
 * the row.
 */
@Composable
fun StaleSessionRow(
    sourceName: String,
    onOpen: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        AppSectionHeader(title = "Нове з $sourceName")
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 5.dp)
                .clip(RoundedCornerShape(AppDimens.RadiusCardLg))
                .testTag("stale_session_cta_${sourceName.lowercase()}"),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "Щоб бачити новинки $sourceName, відкрийте джерело — це оновить сесію.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (onOpen != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = onOpen,
                        shape = RoundedCornerShape(AppDimens.RadiusInner)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Language,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Відкрити $sourceName", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

/**
 * Maps a normalized [SourceBook] to the catalogue card model for display.
 * The id is stable per (source, url) so LazyRow keys never collide.
 */
fun sourceBookToCatalogBook(sourceId: String, book: SourceBook): CatalogBook = CatalogBook(
    id = "$sourceId-${book.url.substringAfterLast('/').substringBefore('?').ifBlank { book.url.hashCode() }}",
    title = book.title,
    author = book.author,
    url = book.url,
    coverImageUrl = book.coverImageUrl,
    seriesTitle = book.seriesTitle,
    seriesIndex = book.seriesIndex
)
