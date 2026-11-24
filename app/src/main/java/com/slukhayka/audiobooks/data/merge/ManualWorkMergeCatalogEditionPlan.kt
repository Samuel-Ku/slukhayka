package com.slukhayka.audiobooks.data.merge

internal class ManualWorkMergeCatalogEditionPlan private constructor(
    private val updates: List<ManualWorkMergeJournal.Row>,
    private val encoding: String,
) {
    fun apply(journal: ManualWorkMergeJournal) {
        for (row in updates) {
            check(journal.updateById(row, journal.text(row, "id", encoding)) == 1)
        }
    }

    companion object {
        fun prepare(
            preview: ManualWorkMergePreview,
            canonicalWorkId: String,
            before: ManualWorkMergeJournal.Image,
            journal: ManualWorkMergeJournal,
        ): ManualWorkMergeCatalogEditionPlan {
            val editions = preview.participants.flatMap { participant ->
                participant.editions.map { edition ->
                    val owner = requireNotNull(manualMergeEditionOwner(preview, participant.work.id, edition)) {
                        "Edition ownership needs a decision"
                    }
                    edition.edition.id to owner
                }
            }
            require(editions.map { it.first }.distinct().size == editions.size) { "Edition has multiple participants" }
            val catalog = editions.mapNotNull { (id, owner) ->
                when (owner) {
                    is ManualWorkMergeEditionOwner.Catalog -> id to owner
                    is ManualWorkMergeEditionOwner.Library -> null
                }
            }.toMap()
            if (catalog.isEmpty()) return ManualWorkMergeCatalogEditionPlan(emptyList(), before.encoding)
            require(before.formatVersion == 4) { "Catalog Edition writes require journal format 4" }
            val rows = before.rows.getValue("editions")
            val ids = rows.map { journal.text(it, "id", before.encoding) }
            require(ids.distinct().size == ids.size &&
                ids.toSet() == preview.snapshot.library.editions.map { it.id }.toSet()) {
                "Edition journal scope differs from preview"
            }
            val byId = rows.associateBy { journal.text(it, "id", before.encoding) }
            val updates = catalog.map { (id, owner) ->
                val row = requireNotNull(byId[id]) { "Catalog Edition before-image missing" }
                require(journal.text(row, "workId", before.encoding) ==
                    owner.workId) { "Catalog Edition owner changed" }
                journal.withText(row, mapOf("workId" to canonicalWorkId), before.encoding)
            }
            return ManualWorkMergeCatalogEditionPlan(updates, before.encoding)
        }

        fun restore(journal: ManualWorkMergeJournal, before: ManualWorkMergeJournal.Image) {
            require(before.formatVersion == 4) { "Catalog Edition restoration requires journal format 4" }
            for (row in before.rows.getValue("editions")) {
                check(journal.updateById(row, journal.text(row, "id", before.encoding)) == 1)
            }
        }
    }
}
