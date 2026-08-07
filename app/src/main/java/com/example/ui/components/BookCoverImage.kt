package com.example.ui.components

import android.graphics.drawable.Drawable
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.data.db.AudiobookEntity
import com.example.ui.theme.*

@Composable
fun BookCoverImage(
    book: AudiobookEntity,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    onImageLoaded: ((Drawable) -> Unit)? = null
) {
    val context = LocalContext.current
    var isError by remember(book.coverImageUrl) { mutableStateOf(false) }
    val imageUrl = book.coverImageUrl

    val imageRequest = remember(imageUrl) {
        if (!imageUrl.isNullOrBlank()) {
            ImageRequest.Builder(context)
                .data(imageUrl)
                .setHeader("User-Agent", "Mozilla/5.0 (Linux; Android 13; Mobile; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                .setHeader("Referer", "https://4read.org/")
                .crossfade(true)
                .allowHardware(false) // Disable hardware bitmaps to prevent Ashmem pinning errors on Android Q+
                .build()
        } else null
    }

    if (!imageUrl.isNullOrBlank() && !isError) {
        AsyncImage(
            model = imageRequest,
            contentDescription = contentDescription ?: book.title,
            modifier = modifier,
            contentScale = contentScale,
            onSuccess = { onImageLoaded?.invoke(it.result.drawable) },
            onError = { isError = true }
        )
    } else {
        // Fallback layout: Elegant dark typography cover with book title & author
        Box(
            modifier = modifier
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surface,
                            MaterialTheme.colorScheme.surfaceVariant,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                        )
                    )
                )
                .padding(6.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Headphones,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = book.title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
                if (book.author.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = book.author,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 9.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}
