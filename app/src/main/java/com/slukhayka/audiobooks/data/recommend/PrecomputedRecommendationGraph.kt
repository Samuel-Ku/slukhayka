package com.slukhayka.audiobooks.data.recommend

import com.squareup.moshi.JsonReader
import okio.Buffer

/** Public content similarity only: no listener identifiers, history, or scores. */
class PrecomputedRecommendationGraph private constructor(
    val generatedAt: Long,
    val expiresAt: Long,
    private val edges: Map<String, Map<String, Double>>
) {
    fun isFresh(now: Long): Boolean = generatedAt <= now + 300_000 && now < expiresAt

    fun similarity(first: String, second: String): Double =
        maxOf(edges[first]?.get(second) ?: 0.0, edges[second]?.get(first) ?: 0.0)

    companion object {
        const val MAX_BYTES = 8 * 1024 * 1024
        private const val MAX_TTL = 7L * 86_400_000

        /** Reject the whole snapshot on an invalid edge; never replace good data with a partial parse. */
        fun parse(text: String, now: Long): PrecomputedRecommendationGraph? = try {
            require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
            val json = JsonReader.of(Buffer().writeUtf8(text)).use { reader ->
                reader.readJsonValue().also { require(reader.peek() == JsonReader.Token.END_DOCUMENT) }
            } as? Map<*, *> ?: return null
            require(json["schemaVersion"] == 1.0)
            require(json["model"] == "minishlab/potion-multilingual-128M")
            require((json["revision"] as? String)?.matches(Regex("[0-9a-f]{40}")) == true)
            require((json["catalogSha256"] as? String)?.matches(Regex("[0-9a-f]{64}")) == true)
            fun timestamp(name: String): Long {
                val value = (json[name] as? Number)?.toDouble() ?: error(name)
                require(value.isFinite() && value >= 0 && value <= 9_007_199_254_740_991.0 && value == value.toLong().toDouble())
                return value.toLong()
            }
            val generated = timestamp("generatedAt")
            val expires = timestamp("expiresAt")
            require(generated >= 0 && expires > generated && expires - generated <= MAX_TTL)
            val keys = json["workKeys"] as? List<*> ?: return null
            require(keys.size in 2..20_000)
            val names = keys.map { value ->
                (value as? String ?: error("key")).also { require(it.isNotBlank() && it.length <= 512) }
            }
            require(names.toSet().size == names.size)
            val rows = json["neighbors"] as? List<*> ?: return null
            require(rows.size == names.size)
            val edges = names.indices.associate { index ->
                val row = rows[index] as? List<*> ?: error("row")
                require(row.size <= 32)
                val neighbors = mutableMapOf<String, Double>()
                for (value in row) {
                    val edge = value as? List<*> ?: error("edge")
                    require(edge.size == 2)
                    val rawTarget = (edge[0] as? Number)?.toDouble() ?: error("target")
                    val target = rawTarget.toInt()
                    require(rawTarget == target.toDouble() && target in names.indices && target != index)
                    val score = (edge[1] as? Number)?.toDouble() ?: error("score")
                    require(score.isFinite() && score > 0.0 && score <= 1.0)
                    require(neighbors.put(names[target], score) == null)
                }
                names[index] to neighbors.toMap()
            }
            PrecomputedRecommendationGraph(generated, expires, edges).also { require(it.isFresh(now)) }
        } catch (_: Exception) {
            null
        }
    }
}
