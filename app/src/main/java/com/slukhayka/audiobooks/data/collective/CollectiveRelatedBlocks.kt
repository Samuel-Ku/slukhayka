package com.slukhayka.audiobooks.data.collective

import com.slukhayka.audiobooks.data.merge.MergeKey
import com.slukhayka.audiobooks.data.source.SourceBookDetail
import com.slukhayka.audiobooks.data.source.SourceIds

/** Builds an observation from an already received SluhayUA detail; performs no source I/O. */
fun collectiveRelatedBlock(sourceId: String, detail: SourceBookDetail): CollectiveRefreshOutcome {
    if (sourceId != SourceIds.SLUHAYUA || detail.title.isBlank() || detail.url.isBlank() || detail.related.isEmpty()) {
        return CollectiveRefreshOutcome.Empty
    }
    val seenUrls = mutableSetOf(detail.url)
    val seenWorks = mutableSetOf<String>().apply {
        MergeKey.keyFor(detail.title, detail.author).takeIf { it.isNotBlank() }?.let { add(it) }
    }
    val cards = detail.related.mapNotNull { card ->
        if (card.title.isBlank() || card.url.isBlank()) return@mapNotNull null
        val workKey = MergeKey.keyFor(card.title, card.author)
        if (card.url in seenUrls || (workKey.isNotBlank() && workKey in seenWorks)) return@mapNotNull null
        seenUrls.add(card.url)
        if (workKey.isNotBlank()) seenWorks.add(workKey)
        CollectiveBlockCard(sourceId, card.url, card.title, card.author, card.coverImageUrl)
    }
    if (cards.isEmpty()) return CollectiveRefreshOutcome.Empty
    val kind = CollectiveBlockKind.RECOMMENDATIONS
    return CollectiveRefreshOutcome.Success(
        CollectiveFeedBlock(
            blockKey = collectiveBlockKey(sourceId, kind), sourceId = sourceId, kind = kind,
            name = "До «${detail.title}»", provenanceUrl = detail.url,
            cards = cards,
            fetchedAt = 0L, staleAfter = 0L, version = 0L,
            lastAttempt = CollectiveAttempt(0L, CollectiveAttemptStatus.SUCCESS)
        )
    )
}
