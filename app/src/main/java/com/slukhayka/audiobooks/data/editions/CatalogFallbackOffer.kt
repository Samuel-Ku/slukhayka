package com.slukhayka.audiobooks.data.editions

import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.source.SourceAccessPolicy

/**
 * #530 — the real-data half of #519's action: it reads the Source rows a book
 * actually has, classifies each one against the Edition the listener is in,
 * drops the refused or cooling-down ones from the OFFER (without touching the
 * rows), and returns the ordered candidates. Zero network requests — the
 * action never searches a Source on its own (a new search belongs to the
 * listener's explicit request).
 */
class CatalogFallbackOffer(
    private val dao: AudiobookDao,
    private val cooldown: SourceCooldownStore? = null,
    private val clock: () -> Long = System::currentTimeMillis
) {

    /**
     * The ordered offer for one book. [currentSourceId] is the source the
     * listener is using right now (null when nothing is playing); [refused]
     * are the sources the listener's own moderation has removed.
     */
    suspend fun offer(
        bookId: String,
        currentSourceId: String? = null,
        refused: Set<String> = emptySet()
    ): List<FallbackCandidate> {
        val sources = dao.getSourcesForBookSync(bookId)
        if (sources.isEmpty()) return emptyList()
        val currentSource = sources.firstOrNull { it.type == currentSourceId }
        val currentEditionId = currentSource?.editionId
        val now = clock()
        val facts = sources
            .filterNot { it.type in refused }
            .filter { source -> cooldown?.isEligible(source.type, now) ?: true }
            .map { source ->
                val sameEdition = !currentEditionId.isNullOrBlank() &&
                    source.editionId == currentEditionId
                SourceAvailabilityFacts(
                    sourceId = source.type,
                    accessMode = SourceAccessPolicy.modeFor(source.type),
                    sameEdition = sameEdition,
                    // A different Edition has no proven chapter mapping, so it
                    // can never auto-start.
                    chapterMappingSafe = sameEdition,
                    isLocal = source.type == LOCAL_SOURCE_ID || source.url.isBlank()
                )
            }
        return FallbackCandidateBuilder.build(
            facts = facts,
            currentSourceId = currentSourceId
        )
    }

    /** The one candidate the action may start by itself, or null (ask first). */
    suspend fun autoStartable(
        bookId: String,
        currentSourceId: String? = null,
        refused: Set<String> = emptySet()
    ): FallbackCandidate? = FallbackCandidateOrder.autoStartable(
        offer(bookId, currentSourceId, refused)
    )

    companion object {
        /** The registry id of a local import. */
        const val LOCAL_SOURCE_ID: String = "local"
    }
}
