package com.slukhayka.audiobooks.data.merge

import com.slukhayka.audiobooks.data.db.GenreAssertionProvenance
import com.slukhayka.audiobooks.data.facets.FacetIdentity
import com.slukhayka.audiobooks.data.merge.ManualWorkMergeJournal.Row

/** Complete raw catalog transformations are decided and collision-checked before the first write. */
internal class ManualWorkMergeCatalogPlan private constructor(
    private val before: Map<String, List<Row>>,
    private val after: Map<String, List<Row>>,
) {
    fun apply(journal: ManualWorkMergeJournal) {
        for (table in ManualWorkMergeJournal.catalogTables) for (row in before.getValue(table)) {
            check(journal.deleteByKey(row) == 1)
        }
        for (table in ManualWorkMergeJournal.catalogTables) for (row in after.getValue(table)) journal.insert(row)
    }

    companion object {
        fun prepare(
            preview: ManualWorkMergePreview, image: ManualWorkMergeJournal.Image,
            canonical: String, journal: ManualWorkMergeJournal,
        ): ManualWorkMergeCatalogPlan {
            val catalog = preview.snapshot.catalog
            val roots = preview.participants.map { it.work.id }.toSet()
            val encoding = image.encoding
            fun text(row: Row, column: String) = journal.text(row, column, encoding)
            val expected = mapOf(
                "series_members" to catalog.seriesMembers.map { listOf(it.workId, it.seriesId) },
                "work_facets" to catalog.workFacets.map { listOf(it.workId) },
                "work_facet_series" to catalog.facetSeries.map { listOf(it.workId, it.seriesId) },
                "genre_assertion_states" to catalog.genreStates.map { listOf(it.workId, it.sourceId) },
                "genre_assertions" to catalog.genreAssertions.map { listOf(it.id) },
                "work_genres" to catalog.workGenres.map { listOf(it.workId, it.genreId, it.sourceId) },
                "edition_facets" to catalog.editionFacets.map { listOf(it.editionId) },
                "embedding_vectors" to catalog.vectors.map { listOf(it.workId) },
            )
            val before = ManualWorkMergeJournal.catalogTables.associateWith { table ->
                image.rows.getValue(table).also { rows ->
                    val keys = rows.map { journal.key(it, encoding) }
                    require(keys.distinct().size == rows.size && keys.toSet() == expected.getValue(table).toSet()) {
                        "Catalog raw ownership needs a participant decision: $table"
                    }
                    require(rows.all { text(it, "workId") in roots }) { "Catalog canonical ownership needs a decision" }
                }
            }
            val result = ManualWorkMergeJournal.catalogTables.associateWith { mutableListOf<Row>() }
            fun moved(row: Row) = journal.withText(row, mapOf("workId" to canonical), encoding)
            fun samePayload(a: Row, b: Row) = journal.sameRow(moved(a), moved(b))
            fun deterministic(rows: List<Row>): Row = rows.minWith { a, b -> journal.compareRows(a, b) }

            val people = before.getValue("work_facets")
            for (row in people) journal.integer(row, "updatedAt")
            val known = people.mapNotNull { journal.nullableText(it, "canonicalAuthorId", encoding) }.distinct()
            require(known.size <= 1 && known.all { id -> catalog.authors.any { it.id == id } }) { "Work author facet needs a decision" }
            if (people.isNotEmpty()) {
                val candidates = if (known.isEmpty()) people else people.filter { journal.nullableText(it, "canonicalAuthorId", encoding) == known.single() }
                val winner = if (known.isEmpty()) deterministic(candidates) else {
                    val newest = candidates.maxOf { journal.integer(it, "updatedAt") }
                    deterministic(candidates.filter { journal.integer(it, "updatedAt") == newest })
                }
                result.getValue("work_facets") += moved(winner)
            }

            val members = before.getValue("series_members")
            for ((series, rows) in members.groupBy { text(it, "seriesId") }) {
                require(catalog.series.any { it.id == series }) { "Series dictionary missing" }
                rows.forEach {
                    val position = journal.integer(it, "position")
                    require(position in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
                    val resolved = journal.value(it, "resolvedAt")
                    require(resolved.type == "integer" || resolved.type == "null")
                }
                require(rows.all { samePayload(rows.first(), it) }) { "Series position or resolution needs a decision" }
                val winner = deterministic(rows)
                result.getValue("series_members") += moved(winner)
                result.getValue("work_facet_series") += Row("work_facet_series", listOf(
                    journal.value(moved(winner), "workId"), journal.value(winner, "seriesId"),
                ))
            }
            require(before.getValue("work_facet_series").all { row -> catalog.series.any { it.id == text(row, "seriesId") } }) {
                "Facet Series dictionary missing"
            }

            val states = before.getValue("genre_assertion_states")
            val stateKeys = states.map { text(it, "workId") to text(it, "sourceId") }.toSet()
            val assertions = before.getValue("genre_assertions")
            require(assertions.all { (text(it, "workId") to text(it, "sourceId")) in stateKeys &&
                catalog.genres.any { genre -> genre.id == text(it, "genreId") } }) { "Genre document ownership needs a decision" }
            val links = before.getValue("work_genres")
            require(links.map { Triple(text(it, "workId"), text(it, "sourceId"), text(it, "genreId")) }.toSet() ==
                assertions.map { Triple(text(it, "workId"), text(it, "sourceId"), text(it, "genreId")) }.toSet()) {
                "Genre projection needs a document decision"
            }
            fun canonicalAssertions(state: Row): List<Row> = assertions.filter {
                text(it, "workId") == text(state, "workId") && text(it, "sourceId") == text(state, "sourceId")
            }.map { row ->
                journal.integer(row, "observedAt")
                val id = FacetIdentity.boundedId("assertion-row", "$canonical|${text(row, "sourceId")}|${text(row, "assertionId")}|${text(row, "genreId")}")
                journal.withText(row, mapOf("workId" to canonical, "id" to id), encoding)
            }.sortedWith { a, b -> journal.compareRows(a, b) }
            fun rank(row: Row) = GenreAssertionProvenance.fromWireName(text(row, "provenance")).rank
            fun equivalent(a: Row, b: Row): Boolean {
                val left = canonicalAssertions(a); val right = canonicalAssertions(b)
                return samePayload(a, b) && left.size == right.size && left.zip(right).all { (x, y) -> journal.sameRow(x, y) }
            }
            for ((_, documents) in states.groupBy { text(it, "sourceId") }) {
                documents.forEach { journal.integer(it, "documentUpdatedAt") }
                val priority = documents.maxOf(::rank)
                val ranked = documents.filter { rank(it) == priority }
                val newest = ranked.maxOf { journal.integer(it, "documentUpdatedAt") }
                val tied = ranked.filter { journal.integer(it, "documentUpdatedAt") == newest }
                require(tied.all { equivalent(tied.first(), it) }) { "Equal genre documents need a decision" }
                val winner = deterministic(tied)
                result.getValue("genre_assertion_states") += moved(winner)
                val winning = canonicalAssertions(winner)
                result.getValue("genre_assertions") += winning
                for ((_, grouped) in winning.groupBy { text(it, "genreId") }) {
                    val row = grouped.first()
                    result.getValue("work_genres") += Row("work_genres", listOf(
                        journal.value(row, "workId"), journal.value(row, "genreId"), journal.value(row, "sourceId"),
                    ))
                }
            }

            for (row in before.getValue("edition_facets")) {
                val owner = text(row, "workId"); val editionId = text(row, "editionId")
                val participant = preview.participants.single { it.work.id == owner }
                val edition = requireNotNull(participant.editions.singleOrNull { it.edition.id == editionId }) { "Edition facet owner missing" }
                val bookId = requireNotNull(edition.bookId) { "Edition facet library anchor missing" }
                require(edition.edition.workId == bookId &&
                    preview.snapshot.library.entries.any { it.id == bookId && it.workId == owner }) {
                    "Edition facet owner needs a decision"
                }
                result.getValue("edition_facets") += moved(row)
            }
            // Vectors are derived: no donor vector is valid under the approved canonical identity.
            for ((table, rows) in result) {
                val keys = rows.map { journal.key(it, encoding) }
                require(keys.distinct().size == rows.size) { "Planned catalog key collision" }
                for (row in rows) {
                    val occupant = journal.rowByKey(table, journal.key(row, encoding))
                    require(occupant == null || before.getValue(table).any { journal.sameRow(it, occupant) }) {
                        "An outside catalog row owns the planned key"
                    }
                    if (table == "genre_assertions" && occupant != null) {
                        require(listOf("assertionId", "sourceId", "genreId").all {
                            journal.sameCell(journal.value(occupant, it), journal.value(row, it))
                        }) { "A different assertion tuple owns the generated key" }
                    }
                }
            }
            return ManualWorkMergeCatalogPlan(before.mapValues { it.value.toList() }, result.mapValues { it.value.toList() })
        }

        fun restore(before: ManualWorkMergeJournal.Image, after: ManualWorkMergeJournal.Image, journal: ManualWorkMergeJournal) {
            for (table in ManualWorkMergeJournal.catalogTables) for (row in after.rows.getValue(table)) {
                check(journal.deleteByKey(row) == 1)
            }
            for (table in ManualWorkMergeJournal.catalogTables) for (row in before.rows.getValue(table)) journal.insert(row)
        }
    }
}
