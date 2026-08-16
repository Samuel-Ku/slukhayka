package com.example.data.universe

import com.example.data.collections.MiniJson

/**
 * Spec-25 (#171) — strict JSON decoding for the curated universe assets
 * (pure JVM, shares the ONE [MiniJson] parser with the collections module).
 * Same doctrine as [com.example.data.collections.CollectionJson]: the asset
 * format is fixed and curated, so the decoder is deliberately strict — a
 * malformed asset never crashes the app, it simply contributes no universe
 * (best-effort load).
 *
 * Expected shape:
 * ```
 * { "id": "first-law", "name": "Перший закон", "series": [
 *     { "title": "Перший закон", "aliases": ["…"], "urls": ["…"] },
 *     { "title": "Епоха божевілля", "aliases": [], "urls": [] }
 * ] }
 * ```
 */
object UniverseJson {

    /** Decodes one asset text into a [UniverseList], or `null` when the text
     *  is not a valid universe object (or lacks id/name/series). */
    fun decode(text: String): UniverseList? {
        return try {
            val obj = MiniJson.parse(text) as? Map<*, *> ?: return null
            val id = obj["id"] as? String ?: return null
            val name = obj["name"] as? String ?: return null
            if (id.isBlank() || name.isBlank()) return null
            val series = when (val raw = obj["series"]) {
                null -> emptyList()
                is List<*> -> raw.map { entry ->
                    // Strict: any malformed entry invalidates the whole
                    // universe — a curated asset either parses fully or is
                    // absent, so a release review catches data bugs.
                    val map = entry as? Map<*, *> ?: return null
                    val title = map["title"] as? String ?: return null
                    if (title.isBlank()) return null
                    UniverseSeries(
                        title = title,
                        aliases = stringList(map["aliases"]) ?: return null,
                        urls = stringList(map["urls"]) ?: return null
                    )
                }
                else -> return null
            }
            if (series.isEmpty()) return null
            UniverseList(id, name, series)
        } catch (e: Exception) {
            null
        }
    }

    private fun stringList(raw: Any?): List<String>? = when (raw) {
        null -> emptyList()
        is List<*> -> raw.map { it as? String ?: return null }
        else -> null
    }
}
