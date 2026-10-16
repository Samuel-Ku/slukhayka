package com.slukhayka.audiobooks.data.recommend

import com.slukhayka.audiobooks.data.collections.MiniJson
import java.io.File

/** Preregistered relevance labels, resolved before any embedding or metric is computed. */
object RecommendationEvalCohorts {
    data class Cohort(val id: String, val workIds: List<String>)

    fun load(file: File, catalog: RecommendationEvalCatalog.Catalog): List<Cohort> {
        val root = MiniJson.parse(file.readText()) as? Map<*, *> ?: error("Malformed relevance registry")
        require((root["schemaVersion"] as? Number)?.toInt() == 1)
        val entries = root["cohorts"] as? List<*> ?: error("No preregistered cohorts")
        val cohorts = entries.map { raw ->
            val cohort = raw as? Map<*, *> ?: error("Malformed cohort")
            val id = cohort["id"] as? String ?: error("Cohort id absent")
            val works = cohort["works"] as? List<*> ?: error("Cohort Works absent")
            val ids = works.map { work ->
                val target = work as? Map<*, *> ?: error("Malformed Work reference")
                val title = target["title"] as? String ?: error("Work title absent")
                val surname = target["authorSurname"] as? String ?: error("Work author absent")
                val aliases = listOf(title) + (target["titleAliases"] as? List<*>)?.filterIsInstance<String>().orEmpty()
                val titles = aliases.map(RecommendationEvalCatalog::normalizedTitle).toSet()
                val matches = catalog.works.filter {
                    RecommendationEvalCatalog.normalizedTitle(it.title) in titles &&
                        it.author.contains(surname, ignoreCase = true)
                }
                require(matches.size == 1) {
                    "Preregistered $id / $title / $surname resolves to ${matches.size} Works: ${matches.map { it.id }}. Resolve identity before scoring; never replace labels after a result."
                }
                matches.single().id
            }
            require(ids.size >= 2 && ids.distinct().size == ids.size) { "Cohort needs distinct relevant Works" }
            Cohort(id, ids)
        }
        require(cohorts.isNotEmpty() && cohorts.map { it.id }.distinct().size == cohorts.size)
        return cohorts
    }
}
