package com.example.ui.components

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import coil.compose.AsyncImage
import com.example.data.db.AudiobookEntity

@Composable
fun BookCoverImage(
    book: AudiobookEntity,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    val imageUrl = book.coverImageUrl
    if (!imageUrl.isNullOrBlank()) {
        AsyncImage(
            model = imageUrl,
            contentDescription = contentDescription ?: book.title,
            modifier = modifier,
            contentScale = contentScale,
            placeholder = painterResource(id = book.coverDrawableRes),
            error = painterResource(id = book.coverDrawableRes)
        )
    } else {
        Image(
            painter = painterResource(id = book.coverDrawableRes),
            contentDescription = contentDescription ?: book.title,
            modifier = modifier,
            contentScale = contentScale
        )
    }
}
