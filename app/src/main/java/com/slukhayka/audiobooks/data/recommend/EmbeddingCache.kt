package com.slukhayka.audiobooks.data.recommend

import java.io.File

/**
 * File-backed cache of catalogue embeddings (spec-19 Q7). Keyed by a
 * deterministic [catalogVersion] — when the catalogue changes the version
 * changes, and the cache simply misses. Embeddings are derived data: they
 * are recomputable, so no Room table, no schema change. Pure JVM (the
 * caller supplies the directory), no Android APIs.
 *
 * Format: one line per book — `id<TAB>f1,f2,...,fN` — so a few thousand
 * 384-dim rows round-trip as a small text file.
 */
class EmbeddingCache(
    private val dir: File
) {
    /** Reads the cache for [version], or null on a miss / any corruption. */
    fun load(version: String): Map<String, FloatArray>? {
        val file = cacheFile(version)
        if (!file.exists()) return null
        return try {
            val result = LinkedHashMap<String, FloatArray>()
            for (line in file.readLines()) {
                val parts = line.split('\t')
                if (parts.size != 2) return null
                val floats = parts[1].split(',')
                    .map { it.toFloatOrNull() }
                    .takeIf { list -> list.size >= 1 && list.none { it == null } }
                    ?: return null
                result[parts[0]] = FloatArray(floats.size) { floats[it]!! }
            }
            result
        } catch (e: Exception) {
            null
        }
    }

    /** Writes the vectors for [version] atomically (tmp + rename). */
    fun save(version: String, vectors: Map<String, FloatArray>) {
        if (!dir.exists()) dir.mkdirs()
        val tmp = File(dir, "vectors_$version.tmp")
        tmp.writeText(
            vectors.entries.joinToString("\n") { (id, vec) ->
                id + '\t' + vec.joinToString(",")
            }
        )
        tmp.renameTo(cacheFile(version))
    }

    private fun cacheFile(version: String): File = File(dir, "vectors_$version")

    companion object {
        /**
         * A deterministic catalogue version: SHA-256 over the sorted
         * `id|text` pairs, truncated — any catalogue change invalidates the
         * cache, identical catalogues reuse it.
         */
        fun catalogVersion(catalog: List<RecommendationEngine.Candidate>): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val joined = catalog
                .sortedBy { it.id }
                .joinToString("\n") { "${it.id}|${it.text}" }
            return digest.digest(joined.toByteArray())
                .take(8)
                .joinToString("") { "%02x".format(it) }
        }
    }
}
