package com.slukhayka.audiobooks.ui.components

import android.graphics.drawable.Drawable
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.source.AndroidSourceCookieProvider
import com.slukhayka.audiobooks.data.source.coverHeadersFor
import com.slukhayka.audiobooks.ui.displayAuthor
import com.slukhayka.audiobooks.ui.theme.*

/**
 * Accessibility contract for a Work cover.
 *
 * A cover inside a card that already exposes the Work title is decorative.
 * A standalone cover is meaningful and owns one caller-supplied description.
 */
sealed interface BookCoverSemantics {
    data object Decorative : BookCoverSemantics

    data class Meaningful(val description: String) : BookCoverSemantics {
        init {
            require(description.isNotBlank()) {
                "A meaningful Work cover needs a non-blank description"
            }
        }
    }
}

/**
 * Genre-mapped accent for cover-fallback art (spec-22 T3). When a cover is
 * blocked or absent, the fallback gradient blends this colour into the
 * surface tones so the placeholder still reads as "that genre's book".
 * Pure function — unit-testable, no theme dependency.
 *
 * Returns null for unknown/blank genres and the «4read Каталог» placeholder
 * genre, so the caller keeps its default (brand) accent unchanged.
 */
fun genreAccentColor(genre: String?): Color? {
    val g = genre?.trim()?.lowercase() ?: return null
    if (g.contains("4read")) return null
    return when {
        g.contains("cyberpunk") || g.contains("киберпанк") || g.contains("кіберпанк") -> Color(0xFF9C6BFF)  // neon violet
        g.contains("фантастик") || g.contains("sci-fi") || g.contains("фэнтези") || g.contains("фентезі") -> Color(0xFF5C6BC0) // cosmic indigo
        g.contains("класик") || g.contains("классик") -> Color(0xFFE9A13B)  // warm amber — the brand hue
        g.contains("детектив") -> Color(0xFF78909C)   // deep slate
        g.contains("антиутоп") -> Color(0xFF26A69A)   // teal
        g.contains("жах") || g.contains("ужас") -> Color(0xFFC62828)   // deep red
        g.contains("пригод") || g.contains("приключен") -> Color(0xFF43A047) // adventure green
        g.contains("роман") || g.contains("любов") -> Color(0xFFEC6E7A) // rose
        else -> null
    }
}

@Composable
fun BookCoverImage(
    book: AudiobookEntity,
    semantics: BookCoverSemantics,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    onImageLoaded: ((Drawable) -> Unit)? = null
) {
    val context = LocalContext.current
    var isError by remember(book.coverImageUrl) { mutableStateOf(false) }
    val imageUrl = book.coverImageUrl
    val resolvedContentDescription = when (semantics) {
        BookCoverSemantics.Decorative -> null
        is BookCoverSemantics.Meaningful -> semantics.description
    }

    val imageRequest = remember(imageUrl) {
        if (!imageUrl.isNullOrBlank()) {
            ImageRequest.Builder(context)
                .data(imageUrl)
                // Spec-38: UA rides the shared image loader's browser identity.
                .applySourceCoverHeaders(imageUrl)
                .crossfade(true)
                .allowHardware(false) // Disable hardware bitmaps to prevent Ashmem pinning errors on Android Q+
                .build()
        } else null
    }

    if (!imageUrl.isNullOrBlank() && !isError) {
        AsyncImage(
            model = imageRequest,
            contentDescription = resolvedContentDescription,
            modifier = modifier,
            contentScale = contentScale,
            onSuccess = { onImageLoaded?.invoke(it.result.drawable) },
            onError = {
                Log.w("BookCover", "Не вдалося завантажити обкладинку: $imageUrl", it.result.throwable)
                isError = true
            }
        )
    } else {
        // Fallback layout: genre-tinted typographic cover with book title &
        // author (spec-22 T3). The genre tint is a soft glow behind the mark,
        // NOT a full-height wash: on a shelf tile the old vertical gradient
        // pooled into a brown band across the bottom third and read as dirt
        // rather than as "that genre's book" (device review, 2026-09-16).
        // spec-54 T11 (#862) — a FLAT fill with large typography, not a wash.
        // A known genre carries its own colour; an unknown one gets the brand
        // accent rather than a dead grey rectangle.
        val fallbackAccent = genreAccentColor(book.genre) ?: MaterialTheme.colorScheme.primary
        // The text colour is PICKED BY MEASUREMENT, not by taste: white while it
        // clears the 4.5:1 floor on this fill, otherwise near-black. Both
        // candidates are checked, so no genre can produce unreadable type.
        val onAccent = if (
            com.slukhayka.audiobooks.ui.theme.ColorContrast.meetsTextFloor(Color.White, fallbackAccent)
        ) {
            Color.White
        } else {
            Color(0xFF101418)
        }
        val initials = book.title.trim()
            .split(' ')
            .filter { it.isNotBlank() }
            .take(2)
            .map { it.first().uppercaseChar() }
            .joinToString("")
        Box(
            modifier = modifier
                .clearAndSetSemantics {
                    resolvedContentDescription?.let { contentDescription = it }
                }
                .background(fallbackAccent),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (initials.isNotBlank()) {
                    Text(
                        text = initials,
                        color = onAccent,
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        overflow = TextOverflow.Clip
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = book.title,
                    color = onAccent,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
                if (book.displayAuthor.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = book.displayAuthor,
                        // No alpha: the floor is measured against the FULL text
                        // colour, so a faded line could silently fail it.
                        color = onAccent,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

/**
 * Applies the source-owned cover policy without exposing source or cookie
 * mechanics to a Compose caller.
 */
fun ImageRequest.Builder.applySourceCoverHeaders(url: String): ImageRequest.Builder {
    AndroidSourceCookieProvider.coverHeadersFor(url).forEach { (name, value) ->
        setHeader(name, value)
    }
    return this
}
