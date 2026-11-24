package com.slukhayka.audiobooks.data.collective

import com.slukhayka.audiobooks.data.source.SourceAccessMode
import com.slukhayka.audiobooks.data.source.SourceFacts
import com.slukhayka.audiobooks.data.source.SourceRegistry

/**
 * #523 — the sources a collective Огляд block covers: the DIRECT Ukrainian
 * catalogue sources of the first vertical slice (#521), in registry order.
 * The registry owns the facts; no source id is hardcoded here.
 */
fun collectiveBlockSources(
    entries: List<SourceFacts> = SourceRegistry.entries
): List<SourceFacts> = entries
    .filter { facts ->
        facts.id != "local" &&
            !facts.scam &&
            facts.accessMode == SourceAccessMode.DIRECT &&
            facts.contentLanguage == "uk"
    }
    .sortedBy { it.order }

/**
 * #523/#1238 — the new-arrivals block key of one source. The provenance is
 * the source's registry home, so the identity carries its canonical context
 * (and a source without a known home honestly falls back to the legacy key).
 */
fun newArrivalsBlockKey(sourceId: String): String =
    collectiveBlockKey(
        sourceId,
        CollectiveBlockKind.NEW_ARRIVALS,
        provenanceUrlOf(sourceId)
    )

/**
 * #1238 — the block key of ONE related collection page. Two related
 * collections of the same Source differ by this URL, so they coexist instead
 * of overwriting each other. The cursor is deliberately NOT part of the URL:
 * a next page is the same collection, not a new identity.
 */
fun collectiveGenreBlockKey(sourceId: String, genrePath: String): String =
    collectiveBlockKey(
        sourceId,
        CollectiveBlockKind.COLLECTIONS,
        genreProvenanceUrl(sourceId, genrePath)
    )

/** #1238 — the canonical provenance URL of one genre/related-collection page. */
fun genreProvenanceUrl(sourceId: String, genrePath: String): String {
    val home = provenanceUrlOf(sourceId)
    if (home.isBlank()) return "$sourceId/" + genrePath.trimStart('/')
    return home.trimEnd('/') + "/" + genrePath.trimStart('/')
}

/** The registry home of a source; blank when the registry has no facts for it. */
fun provenanceUrlOf(sourceId: String): String =
    SourceRegistry.facts(sourceId)?.homeUrl.orEmpty()
