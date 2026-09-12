package com.slukhayka.audiobooks.data.collective

import com.slukhayka.audiobooks.data.collections.MiniJson

/**
 * #523 — the persisted shape of one collective block, encoded into the
 * existing `feed_snapshots.cardsJson` column. Reusing that row (sourceId,
 * feedKey = `collective-<kind>`, fetchedAt) means NO schema change: the
 * block's extra facts (name, provenance, version, last attempt) travel inside
 * the same JSON envelope.
 *
 * Encode is the hand-rolled minimal writer (the [MiniJson] convention: no
 * org.json, null fields omitted, never a JSON null literal); decode is
 * best-effort and total — a malformed or foreign document is a cache MISS,
 * never a crash.
 */
object CollectiveFeedBlockCodec {

    fun encode(block: CollectiveFeedBlock): String = obj(
        "blockKey" to str(block.blockKey),
        "sourceId" to str(block.sourceId),
        "kind" to str(block.kind.name),
        "name" to str(block.name),
        "provenanceUrl" to str(block.provenanceUrl),
        "fetchedAt" to block.fetchedAt.toString(),
        "staleAfter" to block.staleAfter.toString(),
        "version" to block.version.toString(),
        "attemptAt" to block.lastAttempt.at.toString(),
        "attemptStatus" to str(block.lastAttempt.status.name),
        "cards" to block.cards.joinToString(prefix = "[", separator = ",", postfix = "]") { card ->
            obj(
                "sourceId" to str(card.sourceId),
                "sourceUrl" to str(card.sourceUrl),
                "title" to str(card.title),
                "author" to str(card.author),
                "coverUrl" to card.coverUrl?.let(::str)
            )
        }
    )

    fun decode(json: String): CollectiveFeedBlock? {
        val map = MiniJson.parse(json) as? Map<*, *> ?: return null
        val blockKey = map["blockKey"] as? String ?: return null
        val sourceId = map["sourceId"] as? String ?: return null
        val kind = (map["kind"] as? String)?.let { name ->
            CollectiveBlockKind.entries.firstOrNull { it.name == name }
        } ?: return null
        val name = map["name"] as? String ?: return null
        val provenanceUrl = map["provenanceUrl"] as? String ?: return null
        val fetchedAt = (map["fetchedAt"] as? Number)?.toLong() ?: return null
        val staleAfter = (map["staleAfter"] as? Number)?.toLong() ?: return null
        val version = (map["version"] as? Number)?.toLong() ?: return null
        val attemptAt = (map["attemptAt"] as? Number)?.toLong() ?: return null
        val attemptStatus = (map["attemptStatus"] as? String)?.let { status ->
            CollectiveAttemptStatus.entries.firstOrNull { it.name == status }
        } ?: return null
        val cards = (map["cards"] as? List<*>).orEmpty().mapNotNull { item ->
            val card = item as? Map<*, *> ?: return@mapNotNull null
            val title = card["title"] as? String ?: return@mapNotNull null
            val sourceUrl = card["sourceUrl"] as? String ?: return@mapNotNull null
            CollectiveBlockCard(
                sourceId = card["sourceId"] as? String ?: sourceId,
                sourceUrl = sourceUrl,
                title = title,
                author = card["author"] as? String ?: "",
                coverUrl = card["coverUrl"] as? String
            )
        }
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
            lastAttempt = CollectiveAttempt(attemptAt, attemptStatus)
        )
    }

    private fun obj(vararg fields: Pair<String, String?>): String =
        fields.mapNotNull { (name, json) -> json?.let { "\"$name\":$it" } }
            .joinToString(prefix = "{", separator = ",", postfix = "}")

    private fun str(value: String): String = buildString {
        append('"')
        for (ch in value) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
        append('"')
    }
}
