package com.slukhayka.audiobooks.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.slukhayka.audiobooks.ui.theme.AppDimens

/**
 * Remote-cover image with the same genre-tinted typographic fallback as
 * BookCoverImage (spec-22 T3). [genre] is optional — catalogue rows usually
 * carry no genre, so they keep the brand-accent gradient unchanged.
 */
@Composable
fun CatalogCoverImage(
    coverImageUrl: String?,
    title: String,
    semantics: BookCoverSemantics,
    modifier: Modifier = Modifier,
    genre: String? = null
) {
    val context = LocalContext.current
    var isError by remember(coverImageUrl) { mutableStateOf(false) }
    val resolvedContentDescription = when (semantics) {
        BookCoverSemantics.Decorative -> null
        is BookCoverSemantics.Meaningful -> semantics.description
    }

    if (!coverImageUrl.isNullOrBlank() && !isError) {
        val request = remember(coverImageUrl) {
            ImageRequest.Builder(context)
                .data(coverImageUrl)
                // Spec-38: UA rides the shared image loader's browser identity.
                .applySourceCoverHeaders(coverImageUrl)
                .crossfade(true)
                .allowHardware(false)
                .build()
        }
        AsyncImage(
            model = request,
            contentDescription = resolvedContentDescription,
            modifier = modifier,
            contentScale = ContentScale.Crop,
            onError = { isError = true }
        )
    } else {
        val fallbackAccent = genreAccentColor(genre)
        Box(
            modifier = modifier
                .clearAndSetSemantics {
                    resolvedContentDescription?.let { contentDescription = it }
                }
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surface,
                            MaterialTheme.colorScheme.surfaceContainerHigh,
                            (fallbackAccent ?: MaterialTheme.colorScheme.primary)
                                .copy(alpha = if (fallbackAccent != null) 0.45f else 0.25f)
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.Headphones,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 6.dp)
                )
            }
        }
    }
}

