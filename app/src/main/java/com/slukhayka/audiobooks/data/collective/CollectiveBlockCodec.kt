package com.slukhayka.audiobooks.data.collective

import com.slukhayka.audiobooks.data.collections.MiniJson

/**
 * #527 / ADR-0028 — the SHARED wire shape of one observed collective block.
 * It carries only public facts: the Source-scoped identity, the provenance
 * URL, the honest time window/version, and the card list in the source's own
 * order. It NEVER carries a search query, a cookie/session header, a
 * contributor identity, a track URL or audio bytes — the allowed-field set is
 * the gate, and a document with any other key is a miss.
 */
object CollectiveBlockCodec {

    const val FIELD_BLOCK_KEY = "blockKey"
    const val FIELD_SOURCE_ID = "sourceId"
    const val FIELD_KIND = "kind"
    const val FIELD_NAME = "name"
    const val FIELD_PROVENANCE_URL = "provenanceUrl"
    const val FIELD_FETCHED_AT = "fetchedAt"
    const val FIELD_STALE_AFTER = "staleAfter"
    const val FIELD_VERSION = "version"
    const val FIELD_CARDS = "cards"

    val ALLOWED_FIELDS: Set<String> = setOf(
        FIELD_BLOCK_KEY,
        FIELD_SOURCE_ID,
        FIELD_KIND,
        FIELD_NAME,
        FIELD_PROVENANCE_URL,
        FIELD_FETCHED_AT,
        FIELD_STALE_AFTER,
        FIELD_VERSION,
        FIELD_CARDS
    )

    private val CARD_FIELDS = setOf("sourceId", "sourceUrl", "title", "author", "coverUrl")

    /** Encodes one block; null when it is empty or its identity is invalid. */
    fun toMap(block: CollectiveFeedBlock): Map<String, Any>? {
        if (block.cards.isEmpty()) return null
        if (block.blockKey.isBlank() || block.sourceId.isBlank()) return null
        if (parseCollectiveBlockKey(block.blockKey) == null) return null
        if (block.name.isBlank() || block.provenanceUrl.isBlank()) return null
        if (block.fetchedAt <= 0L || block.staleAfter < block.fetchedAt) return null
        return buildMap {
            put(FIELD_BLOCK_KEY, block.blockKey)
            put(FIELD_SOURCE_ID, block.sourceId)
            put(FIELD_KIND, block.kind.name)
            put(FIELD_NAME, block.name)
            put(FIELD_PROVENANCE_URL, block.provenanceUrl)
            put(FIELD_FETCHED_AT, block.fetchedAt)
            put(FIELD_STALE_AFTER, block.staleAfter)
            put(FIELD_VERSION, block.version)
            put(
                FIELD_CARDS,
                block.cards.map { card ->
                    buildMap<String, Any> {
                        put("sourceId", card.sourceId)
                        put("sourceUrl", card.sourceUrl)
                        put("title", card.title)
                        put("author", card.author)
                        card.coverUrl?.let { put("coverUrl", it) }
                    }
                }
            )
        }
    }

    /** Decodes one shared block; null on a forbidden/missing/invalid field. */
    fun fromMap(data: Map<String, Any>): CollectiveFeedBlock? {
        if (data.keys.any { it !in ALLOWED_FIELDS }) return null
        val blockKey = data[FIELD_BLOCK_KEY] as? String ?: return null
        val ref = parseCollectiveBlockKey(blockKey) ?: return null
        val sourceId = data[FIELD_SOURCE_ID] as? String ?: return null
        if (sourceId != ref.sourceId) return null
        val kindName = data[FIELD_KIND] as? String ?: return null
        val kind = CollectiveBlockKind.entries.firstOrNull { it.name == kindName } ?: return null
        if (kind != ref.kind) return null
        val name = data[FIELD_NAME] as? String ?: return null
        val provenanceUrl = data[FIELD_PROVENANCE_URL] as? String ?: return null
        val fetchedAt = (data[FIELD_FETCHED_AT] as? Number)?.toLong() ?: return null
        val staleAfter = (data[FIELD_STALE_AFTER] as? Number)?.toLong() ?: return null
        val version = (data[FIELD_VERSION] as? Number)?.toLong() ?: return null
        val cardMaps = data[FIELD_CARDS] as? List<*> ?: return null
        val cards = cardMaps.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            if (map.keys.any { key -> key !in CARD_FIELDS }) return@mapNotNull null
            val title = map["title"] as? String ?: return@mapNotNull null
            val sourceUrl = map["sourceUrl"] as? String ?: return@mapNotNull null
            val cardSourceId = map["sourceId"] as? String ?: return@mapNotNull null
            if (title.isBlank() || sourceUrl.isBlank()) return@mapNotNull null
            CollectiveBlockCard(
                sourceId = cardSourceId,
                sourceUrl = sourceUrl,
                title = title,
                author = map["author"] as? String ?: "",
                coverUrl = map["coverUrl"] as? String
            )
        }
        if (cards.isEmpty()) return null
        return CollectiveFeedBlock(
            blockKey = blockKey,
            sourceId = sourceId,
            kind = kind,
            name = name,
            provenanceUrl = provenanceUrl,
            cards = cards,
            fetchedAt = fetchedAt,
            staleAfter = staleAfter,
            version = version,
            lastAttempt = CollectiveAttempt(fetchedAt, CollectiveAttemptStatus.SUCCESS)
        )
    }

    /** Best-effort decode of a JSON document (the Firestore transport's shape). */
    fun fromJson(json: String): CollectiveFeedBlock? {
        val map = MiniJson.parse(json) as? Map<*, *> ?: return null
        @Suppress("UNCHECKED_CAST")
        return fromMap(map as Map<String, Any>)
    }
}

/** #527 — the ordered high-water mark of the shared block lane. */
data class CollectiveBlockCursor(
    val fetchedAt: Long,
    val documentId: String
)

/** #527 — one bounded ordered page of shared blocks. */
data class CollectiveBlockPage(
    val blocks: List<CollectiveFeedBlock>,
    val nextCursor: CollectiveBlockCursor?
)
