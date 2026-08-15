package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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

/**
 * Returns a genre-themed background gradient brush for fallback artwork
 */
fun getGenreGradient(genre: String?): Brush {
    val lower = genre?.lowercase() ?: ""
    return when {
        lower.contains("cyberpunk") || lower.contains("кіберпанк") || lower.contains("киберпанк") -> {
            Brush.verticalGradient(listOf(Color(0xFF2E0854), Color(0xFF0F172A), CyberPrimary.copy(alpha = 0.4f)))
        }
        lower.contains("фантастика") || lower.contains("sci-fi") || lower.contains("космос") -> {
            Brush.verticalGradient(listOf(Color(0xFF0D1B2A), Color(0xFF1B263B), Color(0xFF00ADB5).copy(alpha = 0.5f)))
        }
        lower.contains("детектив") || lower.contains("трилер") || lower.contains("містика") -> {
            Brush.verticalGradient(listOf(Color(0xFF1F1D2B), Color(0xFF121212), Color(0xFFFF5722).copy(alpha = 0.35f)))
        }
        lower.contains("класика") || lower.contains("классика") || lower.contains("роман") -> {
            Brush.verticalGradient(listOf(Color(0xFF3E2723), Color(0xFF1E1B18), Color(0xFFFFB300).copy(alpha = 0.35f)))
        }
        lower.contains("антиутопія") || lower.contains("антиутопия") -> {
            Brush.verticalGradient(listOf(Color(0xFF37474F), Color(0xFF212121), Color(0xFF00E676).copy(alpha = 0.3f)))
        }
        else -> {
            Brush.verticalGradient(listOf(CyberSurface, CyberCardBg, CyberPrimary.copy(alpha = 0.25f)))
        }
    }
}

/**
 * Returns a genre-appropriate icon
 */
fun getGenreIcon(genre: String?): ImageVector {
    val lower = genre?.lowercase() ?: ""
    return when {
        lower.contains("cyberpunk") || lower.contains("кіберпанк") -> Icons.Default.Memory
        lower.contains("фантастика") || lower.contains("sci-fi") -> Icons.Default.RocketLaunch
        lower.contains("детектив") || lower.contains("трилер") -> Icons.Default.Search
        lower.contains("класика") || lower.contains("роман") -> Icons.Default.AutoStories
        lower.contains("антиутопія") -> Icons.Default.Visibility
        else -> Icons.Default.Headphones
    }
}

@Composable
fun BookCoverImage(
    book: AudiobookEntity,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    onImageLoaded: ((android.graphics.drawable.Drawable) -> Unit)? = null
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
            onError = { isError = true }
        )
    } else {
        // Fallback layout: Genre-aware gradient typography cover
        Box(
            modifier = modifier
                .background(brush = getGenreGradient(book.genre))
                .padding(6.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = getGenreIcon(book.genre),
                    contentDescription = null,
                    tint = CyberPrimary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = book.title,
                    color = CyberTextPrimary,
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
                        color = CyberTextSecondary,
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
