package com.example.data.universe

import com.example.data.collections.MiniJson

/**
 * Spec-25 T2 (#173) — pure JVM parsing of the two Wikidata API shapes the
 * provider uses: `wbsearchentities` (search hits) and `wbgetentities`
 * (claims + labels). No network, no Android — the provider feeds it the
 * fetched JSON and gets typed facts, so the mapping is pinned by fixture
 * tests (prior art: source-adapter fixture tests).
 *
 * Claim access follows the Wikidata JSON structure: `entities[qid].claims[P]
 * [].mainsnak.datavalue.value.id`. Statements without a value
 * (`snaktype: "somevalue"`) are skipped — an absent fact contributes
 * nothing.
 */
object WikidataParser {

    /** Every hit id of a `wbsearchentities` response, in API order. */
    fun searchHitIds(json: String): List<String> {
        val obj = MiniJson.parse(json) as? Map<*, *> ?: return emptyList()
        val search = obj["search"] as? List<*> ?: return emptyList()
        return search.mapNotNull { (it as? Map<*, *>)?.get("id") as? String }
    }

    /**
     * Every hit id of a CirrusSearch response (`action=query&list=search`,
     * spec-26 T2 — the author-narrowed work search). The hits live under
     * `query.search[].title` (the item's QID).
     */
    fun cirrusHitIds(json: String): List<String> {
        val obj = MiniJson.parse(json) as? Map<*, *> ?: return emptyList()
        val query = obj["query"] as? Map<*, *> ?: return emptyList()
        val search = query["search"] as? List<*> ?: return emptyList()
        return search.mapNotNull { (it as? Map<*, *>)?.get("title") as? String }
    }

    /** Every P50 (author) entity id of one entity in a `wbgetentities` response. */
    fun authorIds(json: String, qid: String): List<String> = claimEntityIds(json, qid, "P50")

    /** Every P179 (part of the series) entity id of one entity. */
    fun seriesIds(json: String, qid: String): List<String> = claimEntityIds(json, qid, "P179")

    /** Every P155 (follows) entity id of one entity — the preceding series. */
    fun followsIds(json: String, qid: String): List<String> = claimEntityIds(json, qid, "P155")

    /** Every P156 (followed by) entity id of one entity — the next series. */
    fun followedByIds(json: String, qid: String): List<String> = claimEntityIds(json, qid, "P156")

    /**
     * The display label of one entity, preferring [languages] in order (the
     * provider searches uk → ru → en, so the label follows the same order).
     * Null when the entity or every language label is absent.
     */
    fun label(json: String, qid: String, languages: List<String>): String? {
        val obj = MiniJson.parse(json) as? Map<*, *> ?: return null
        val entity = (obj["entities"] as? Map<*, *>)?.get(qid) as? Map<*, *> ?: return null
        val labels = entity["labels"] as? Map<*, *> ?: return null
        for (language in languages) {
            val value = (labels[language] as? Map<*, *>)?.get("value") as? String
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    private fun claimEntityIds(json: String, qid: String, property: String): List<String> {
        val obj = MiniJson.parse(json) as? Map<*, *> ?: return emptyList()
        val entity = (obj["entities"] as? Map<*, *>)?.get(qid) as? Map<*, *> ?: return emptyList()
        val claims = entity["claims"] as? Map<*, *> ?: return emptyList()
        val propertyClaims = claims[property] as? List<*> ?: return emptyList()
        return propertyClaims.mapNotNull { claim ->
            val mainsnak = (claim as? Map<*, *>)?.get("mainsnak") as? Map<*, *> ?: return@mapNotNull null
            val value = mainsnak["datavalue"] as? Map<*, *> ?: return@mapNotNull null
            val inner = value["value"] as? Map<*, *> ?: return@mapNotNull null
            inner["id"] as? String
        }
    }
}
