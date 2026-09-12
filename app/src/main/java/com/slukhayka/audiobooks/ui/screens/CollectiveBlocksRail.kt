package com.slukhayka.audiobooks.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.collective.CollectiveBlockCard
import com.slukhayka.audiobooks.data.collective.CollectiveFeedBlock
import com.slukhayka.audiobooks.data.source.sourceDisplayName
import com.slukhayka.audiobooks.ui.components.AppSectionHeader
import com.slukhayka.audiobooks.ui.components.MetadataChip
import com.slukhayka.audiobooks.ui.components.PosterCard
import com.slukhayka.audiobooks.ui.components.PosterWidth

/**
 * #523 / ADR-0041 — one collective Огляд block: the source's freshly observed
 * arrivals, rendered from the locally persisted snapshot (so it is readable
 * offline and never blocks on a source), with the provenance line that says
 * whose block this is and each card's source badge. Cards keep the source's
 * own order; the rail is a shelf, so it never paginates on its own.
 */
@Composable
fun CollectiveBlockRail(
    block: CollectiveFeedBlock,
    onCardClick: (CollectiveBlockCard) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.testTag("collective_block_${block.blockKey}")) {
        AppSectionHeader(
            title = block.name,
            subtitle = stringResource(R.string.collective_block_provenance, block.provenanceUrl)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(block.cards, key = { "${it.sourceId}|${it.sourceUrl}" }) { card ->
                Column(
                    modifier = Modifier.width(PosterWidth),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    PosterCard(
                        title = card.title,
                        coverUrl = card.coverUrl,
                        author = card.author.takeIf { it.isNotBlank() },
                        onClick = { onCardClick(card) },
                        testTag = "collective_card_${card.sourceId}_${card.sourceUrl.hashCode()}"
                    )
                    if (card.author.isBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    MetadataChip(source = sourceDisplayName(card.sourceId))
                }
            }
        }
        // The block's own honesty line: when the source last answered and
        // whether the latest attempt succeeded are never hidden.
        Text(
            text = stringResource(
                R.string.collective_block_attempt,
                stringResource(attemptLabelRes(block))
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("collective_block_attempt_${block.blockKey}")
        )
    }
}

private fun attemptLabelRes(block: CollectiveFeedBlock): Int =
    if (block.lastAttempt.status == com.slukhayka.audiobooks.data.collective.CollectiveAttemptStatus.SUCCESS) {
        R.string.collective_block_attempt_ok
    } else {
        R.string.collective_block_attempt_stale
    }
