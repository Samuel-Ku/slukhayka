package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.data.catalog.CatalogBook
import com.example.data.repository.AudiobookRepository.SourceNewFeed
import com.example.data.source.SourceBook
import com.example.ui.components.AppSectionHeader

/**
 * Spec-10 T5 — one «Нове з <джерела>» feed row: the section header plus a
 * horizontal row of book cards, same shape as the existing «Нове на 4read»
 * rows. Extracted as a pure `@Composable` (no ViewModel) so the snapshot seam
 * can pin the row from fixture data.
 */
@Composable
fun SourceFeedRow(
    feed: SourceNewFeed,
    onBookClick: (SourceBook) -> Unit,
    modifier: Modifier = Modifier
) {
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
