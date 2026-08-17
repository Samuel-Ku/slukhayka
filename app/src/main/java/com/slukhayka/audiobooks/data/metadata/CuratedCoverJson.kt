package com.slukhayka.audiobooks.data.metadata

import com.slukhayka.audiobooks.data.collections.MiniJson

/**
 * Spec-30 T3 (#218) — strict JSON decoding for the curated-covers asset
 * (pure JVM, shares the ONE [MiniJson] parser with the collections and
 * universe modules). Same doctrine as the sibling asset decoders: the asset
 * format is fixed and curated, so the decoder is deliberately strict — a
 * malformed asset never crashes the app, it simply contributes no curated
 * covers (best-effort load).
 *
 * Expected shape:
 * ```
 * { "covers": [
 *     { "mergeKey": "кобзар|шевченко", "coverUrl": "https://4read.org/img/kobzar.jpg" },
 *     …
 * ] }
 * ```
 *
 * An entry without a usable mergeKey or coverUrl is dropped (a curated asset
 * with a broken entry still pours its good ones — unlike the universe
 * decoder's all-or-nothing rule, because covers are independent Work facts,
 * not memberships of one set).
 */
object CuratedCoverJson {

    /** Decodes one asset text into the curated covers, or an empty list. */
    fun decode(text: String): List<CuratedCover> {
        return try {
            val obj = MiniJson.parse(text) as? Map<*, *> ?: return emptyList()
            val raw = obj["covers"] as? List<*> ?: return emptyList()
            raw.mapNotNull { entry ->
                val map = entry as? Map<*, *> ?: return@mapNotNull null
                val mergeKey = (map["mergeKey"] as? String)?.trim().orEmpty()
                val coverUrl = (map["coverUrl"] as? String)?.trim().orEmpty()
                if (mergeKey.isBlank() || !CoverSanity.isPlausible(coverUrl)) null
                else CuratedCover(mergeKey, coverUrl)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}