package com.slukhayka.audiobooks.data.recommend

import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.EmbeddingVectorEntity
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Packs a [FloatArray] into a BLOB and hashes the embedded text (#482). */
object VectorCodec {

    fun encode(vector: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (value in vector) buffer.putFloat(value)
        return buffer.array()
    }

    fun decode(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 4) { buffer.getFloat() }
    }

    /** SHA-256 of the exact text that was embedded. */
    fun textHash(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }
}

/**
 * #482 — the Room-backed per-book embedding cache. A row is reused while the
 * Work's text hash matches; a changed text (e.g. a new description) misses and
 * re-embeds only that book, and the cache survives a restart because it is
 * Room, not filesDir. Catalogue-union churn no longer invalidates everything.
 *
 * Best-effort by contract: a failing read is a miss, a failing write leaves
 * the in-memory result usable.
 */
class RoomEmbeddingCache(
    private val dao: AudiobookDao,
    private val clock: () -> Long = System::currentTimeMillis
) {

    /** The vectors whose stored text hash still matches the given text. */
    suspend fun loadFresh(texts: Map<String, String>): Map<String, FloatArray> {
        if (texts.isEmpty()) return emptyMap()
        val rows = runCatching { dao.embeddingVectors(texts.keys.toList()) }
            .getOrDefault(emptyList())
            .associateBy { it.workId }
        val result = LinkedHashMap<String, FloatArray>()
        for ((id, text) in texts) {
            val row = rows[id] ?: continue
            if (row.textHash == VectorCodec.textHash(text)) {
                result[id] = VectorCodec.decode(row.vector)
            }
        }
        return result
    }

    /** Persists computed vectors keyed by the text they were built from. */
    suspend fun save(entries: Map<String, Pair<String, FloatArray>>) {
        if (entries.isEmpty()) return
        val now = clock()
        val rows = entries.map { (id, textAndVector) ->
            EmbeddingVectorEntity(
                workId = id,
                textHash = VectorCodec.textHash(textAndVector.first),
                vector = VectorCodec.encode(textAndVector.second),
                updatedAt = now
            )
        }
        runCatching { dao.upsertEmbeddingVectors(rows) }
    }
}
