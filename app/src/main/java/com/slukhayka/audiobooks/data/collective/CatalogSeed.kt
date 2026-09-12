package com.slukhayka.audiobooks.data.collective

import com.slukhayka.audiobooks.data.collections.MiniJson

/**
 * #532 — the bundled COLD-START seed of verified Ukrainian books. It is the
 * same public shape as a collective card ([CollectiveCardLimits] is the gate),
 * so a seed entry can never carry a search query, a cookie, a contributor
 * identity or a track URL — only the book's public facts and the source that
 * serves it. An entry the limits reject is dropped, never half-imported.
 *
 * The seed is DATA (an asset), so growing it is a content change: a clean
 * install gets a local «Огляд» with no Firestore and no Source request.
 */
object CatalogSeedCodec {

    const val MAX_ENTRIES: Int = 60

    /** Parses the bundled JSON array; malformed or foreign documents are empty. */
    fun parse(json: String): List<CollectiveCardPublication> {
        val root = MiniJson.parse(json) as? List<*> ?: return emptyList()
        return root
            .take(MAX_ENTRIES)
            .mapNotNull { item ->
                @Suppress("UNCHECKED_CAST")
                val map = item as? Map<String, Any> ?: return@mapNotNull null
                CollectiveCardCodec.fromMap(map)
            }
            .filter { CollectiveCardLimits.isPublishable(it) }
    }
}

/**
 * #532 — imports the bundled seed through the ORDINARY merge-on-write path
 * ([SourceCatalog.applyCollectiveCard]): the existing Work/Edition/Source
 * upsert runs, tombstones keep blocking, and a failed or duplicate entry is a
 * no-op. It makes ZERO network requests — a clean install shows local content
 * without Firestore or any Source.
 */
class CatalogSeedImporter(
    private val apply: suspend (CollectiveCardPublication) -> Boolean
) {

    /** @return how many seed entries actually landed this run. */
    suspend fun importOnce(seed: List<CollectiveCardPublication>): Int {
        var imported = 0
        for (entry in seed) {
            val accepted = runCatching { apply(entry) }.getOrDefault(false)
            if (accepted) imported++
        }
        return imported
    }
}
