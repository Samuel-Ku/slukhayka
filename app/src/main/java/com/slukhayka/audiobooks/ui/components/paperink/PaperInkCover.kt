package com.slukhayka.audiobooks.ui.components.paperink

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.ui.theme.PaperInkDimens
import com.slukhayka.audiobooks.ui.theme.PaperInkType

/**
 * Cover art placeholder for the «Папір і голос» primitives.
 *
 * A missing cover is not a grey box: it is a paper-coloured block with the
 * book's initial set in Literata, so the shelf keeps the book's identity. This
 * is the same rule the current `BookCoverImage` fallback follows; the primitives
 * accept it as a composable so screens can swap in the real Coil image without
 * changing their layout.
 */
@Composable
fun PaperInkCover(
    title: String,
    modifier: Modifier = Modifier,
    radius: Dp = PaperInkDimens.RadiusSmall,
    shape: Shape = RoundedCornerShape(radius),
    content: @Composable (() -> Unit)? = null,
) {
    val colorScheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .shadow(10.dp, shape, clip = false)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        colorScheme.surfaceVariant,
                        colorScheme.secondaryContainer,
                    ),
                ),
            )
            .border(1.dp, colorScheme.outlineVariant, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (content != null) {
            content()
        } else {
            Text(
                text = title.trim().take(1).uppercase(),
                style = PaperInkType.BookTitlePage,
                color = colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A flat cover box used inside dense rows where the shadow would be noise. */
@Composable
fun PaperInkCoverFlat(
    title: String,
    modifier: Modifier = Modifier,
    radius: Dp = PaperInkDimens.RadiusSmall,
) {
    val shape = RoundedCornerShape(radius)
    val colorScheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .clip(shape)
            .background(colorScheme.surfaceVariant)
            .border(1.dp, colorScheme.outlineVariant, shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title.trim().take(1).uppercase(),
            style = PaperInkType.SectionTitle,
            color = colorScheme.onSurfaceVariant,
        )
    }
}

/** The full-bleed paper ground of a gallery/migrated screen preview. */
@Composable
fun PaperInkGround(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    )
}
