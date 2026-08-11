package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import com.example.data.source.GlobalSearchResult
import com.example.ui.theme.AppDimens

/**
 * Spec-10 T4 — one global-search result card: a Work with a badge per source
 * that matched. Tapping imports from the found source and plays. Extracted as
 * a pure `@Composable` (no ViewModel) so the snapshot seam can pin the layout
 * and badges without a network or a database.
 */
@Composable
fun GlobalSearchResultCard(
    result: GlobalSearchResult,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = AppDimens.SpaceLg, vertical = AppDimens.SpaceSm)
            .testTag("global_search_result_${result.key}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Cover placeholder: the first letter on the surface container.
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(AppDimens.RadiusCover))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = result.title.firstOrNull()?.toString()?.uppercase() ?: "?",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(AppDimens.SpaceMd))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = result.title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (result.author.isNotBlank()) {
                Text(
                    text = result.author,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (result.sources.isNotEmpty()) {
                Spacer(modifier = Modifier.height(AppDimens.SpaceXs))
                Row(horizontalArrangement = Arrangement.spacedBy(AppDimens.SpaceXs)) {
                    result.sources.forEach { source ->
                        SourceBadgePill(label = source.sourceName)
                    }
                }
            }
        }
        Icon(
            imageVector = Icons.Default.PlayArrow,
            contentDescription = "Відтворити",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(32.dp)
        )
    }
}

/** Small unobtrusive pill: which source(s) carry a book (spec-10 T4). */
@Composable
fun SourceBadgePill(
    label: String,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(AppDimens.RadiusXs),
        modifier = modifier
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
