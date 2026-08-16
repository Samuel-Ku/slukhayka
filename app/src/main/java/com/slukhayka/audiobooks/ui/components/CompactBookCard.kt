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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.ui.displayAuthor
import com.slukhayka.audiobooks.ui.theme.AppDimens

/**
 * ADR-0018 — CompactBookCard: the horizontal-shelf poster (portrait cover,
 * title and author, no default play triangle). Tapping opens the book page.
 *
 * The optional [onNotInterested] adds the Listen-shelf «Не цікаво» dismiss ✕
 * over the cover (wayfinder #62, reversible) — when absent the card keeps its
 * canonical shape unchanged.
 */
@Composable
fun CompactBookCard(
    book: AudiobookEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onNotInterested: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .width(120.dp)
            .clickable { onClick() }
            .testTag("compact_book_${book.id}"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box {
            BookCoverImage(
                book = book,
                contentDescription = book.title,
                modifier = Modifier
                    .width(120.dp)
                    .height(168.dp)
                    .clip(RoundedCornerShape(AppDimens.RadiusCardLg))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(AppDimens.RadiusCardLg))
            )
            if (onNotInterested != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                        .clickable(onClick = onNotInterested)
                        .testTag("not_interested_${book.id}"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Не цікаво",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
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
}
