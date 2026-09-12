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

/** #523 — the new-arrivals block key of one source. */
fun newArrivalsBlockKey(sourceId: String): String =
    collectiveBlockKey(sourceId, CollectiveBlockKind.NEW_ARRIVALS)
