package com.slukhayka.audiobooks.data.recommend

import com.slukhayka.audiobooks.data.collections.MiniJson
import java.io.File
import java.security.MessageDigest

/** Explicit bibliographic identities, separate from the unchanged relevance labels. */
class RecommendationEvalIdentityAliases private constructor(val works: List<Work>) {
    data class Work(val title: String, val author: String, val authorSurname: String,
        val representativeIdentifier: String, val recordIds: List<String>)
    private val byRecord = works.flatMap { work -> work.recordIds.map { it to work } }.toMap()
    fun workFor(identifier: String): Work? = byRecord[identifier]

    companion object {
        fun load(file: File, records: List<Map<*, *>>): RecommendationEvalIdentityAliases {
            val root = MiniJson.parse(file.readText()) as? Map<*, *> ?: error("Malformed identity manifest")
            require((root["schemaVersion"] as? Number)?.toInt() == 1)
            val groups = root["works"] as? List<*> ?: error("Identity Works absent")
            require(groups.isNotEmpty()) { "Identity Works absent" }
            val rawById = records.associateBy { it["identifier"] as? String ?: error("Raw identifier absent") }
            require(rawById.size == records.size) { "Repeated frozen source identifier" }
            fun verifyRecord(entry: Map<*, *>): String {
                val id = entry["identifier"] as? String ?: error("Identity identifier absent")
                val raw = rawById[id]
                require(raw != null) { "Identity recording $id absent from frozen source" }
                val description = raw["description"] as? String ?: ""
                val digest = MessageDigest.getInstance("SHA-256").digest(description.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
                require(raw["title"] == entry["title"] && raw["creator"] == entry["creator"] &&
                    digest == entry["descriptionSha256"]) { "Identity metadata differs from audited recording $id" }
                require(entry["sourceUrl"] == "https://archive.org/details/$id") { "Identity provenance differs for $id" }
                require((entry["reason"] as? String)?.isNotBlank() == true) { "Identity assertion needs its reason" }
                return id
            }
            val works = groups.map { entry ->
                val work = entry as? Map<*, *> ?: error("Malformed identity Work")
                fun field(key: String) = work[key] as? String ?: error("Identity $key absent")
                val members = work["records"] as? List<*> ?: error("Identity records absent")
                val canonicalAuthor = field("author")
                val ids = members.map {
                    val member = it as Map<*, *>
                    val id = verifyRecord(member)
                    require(RecommendationEvalCatalog.sameKnownAuthor(member["creator"] as String, canonicalAuthor)) {
                        "Recording $id does not have the asserted known author $canonicalAuthor"
                    }
                    id
                }
                val representative = field("representativeIdentifier")
                require(ids.isNotEmpty() && representative in ids)
                Work(field("title"), field("author"), field("authorSurname"), representative, ids.toList())
            }
            val assigned = works.flatMap { it.recordIds }
            require(assigned.distinct().size == assigned.size) { "A recording was assigned more than once" }
            require(works.map { it.title to it.author }.distinct().size == works.size) { "Repeated identity Work" }
            val distinct = (root["distinctRelatedRecords"] as? List<*>).orEmpty().map { verifyRecord(it as Map<*, *>) }
            require(distinct.distinct().size == distinct.size && distinct.none { it in assigned }) {
                "A distinct related Work was also asserted as the same Work"
            }
            return RecommendationEvalIdentityAliases(works.toList())
        }
    }
}
