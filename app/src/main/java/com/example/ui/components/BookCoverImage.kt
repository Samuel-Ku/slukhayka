package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material3.Icon
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
    contentScale: ContentScale = ContentScale.Crop
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
        // Fallback layout: Elegant dark typography cover with book title & author
        Box(
            modifier = modifier
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            CyberSurface,
                            CyberCardBg,
                            CyberPrimary.copy(alpha = 0.25f)
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
