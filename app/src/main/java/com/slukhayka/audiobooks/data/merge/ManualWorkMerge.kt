package com.slukhayka.audiobooks.data.merge

import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.BookmarkEntity
import com.slukhayka.audiobooks.data.db.ChapterEntity
import com.slukhayka.audiobooks.data.db.EditionEntity
import com.slukhayka.audiobooks.data.db.PlaybackProgressEntity
import com.slukhayka.audiobooks.data.db.WorkEntity

/**
 * Read-only typed preview of two Works and their dependency closure (#968).
 * Every projection comes from one DAO-owned Room transaction. It approves
 * no canonical identity and is not an apply/Undo or physical before-image plan.
 */
class ManualWorkMerge(private val dao: AudiobookDao) {
    suspend fun prepareManualWorkMerge(a: String, b: String): ManualWorkMergePreview {
        require(a != b) { "Two distinct Work identities are required" }
        val snapshot = dao.readManualWorkMergeRows(listOf(a, b))
        val participants = listOf(a, b).mapNotNull { workId ->
            val work = snapshot.library.works.firstOrNull { it.id == workId } ?: return@mapNotNull null
            val entryIds = snapshot.library.entries.filter { it.workId == workId }.map { it.id }.toSet()
            val narrations = snapshot.library.editions.filter { edition ->
                edition.workId == workId || edition.workId in entryIds ||
                    associatedBookIds(snapshot, edition.id).any { it in entryIds }
            }.sortedWith { first, second ->
                second.addedAt.compareTo(first.addedAt).takeIf { it != 0 }
                    ?: compareMergeUtf8(first.id, second.id)
            }.map { edition ->
                val actualEntryIds = snapshot.library.entries.map { it.id }.toSet()
                val associations = buildSet {
                    if (edition.workId in actualEntryIds) add(edition.workId)
                    addAll(associatedBookIds(snapshot, edition.id).filter { it in actualEntryIds })
                }
                ManualWorkMergeEditionPreview(
                    bookId = associations.singleOrNull()?.takeIf { it in entryIds },
                    edition = edition,
                    chapters = immutableMergeList(snapshot.library.chapters.filter { it.editionId == edition.id }
                        .sortedWith { first, second -> first.chapterIndex.compareTo(second.chapterIndex)
                            .takeIf { it != 0 } ?: compareMergeUtf8(first.id, second.id) }),
                    progress = snapshot.library.progress.firstOrNull { it.editionId == edition.id },
                    bookmarks = immutableMergeList(snapshot.library.bookmarks.filter { it.editionId == edition.id }
                        .sortedWith(compareBy<BookmarkEntity> { it.timestampSeconds }.thenBy { it.id })),
                )
            }
            ManualWorkMergeParticipant(work, immutableMergeList(narrations))
        }
        return ManualWorkMergePreview(
            participants = immutableMergeList(participants),
            snapshot = snapshot,
            counts = ManualWorkMergeCounts.from(snapshot),
            fingerprint = ManualWorkMergeFingerprint.of(snapshot),
            conflicts = snapshot.conflicts,
        )
    }

    private fun associatedBookIds(snapshot: ManualWorkMergeSnapshot, editionId: String): Set<String> = buildSet {
        snapshot.library.sources.filter { it.editionId == editionId }.forEach { add(it.bookId) }
        snapshot.library.chapters.filter { it.editionId == editionId }.forEach { add(it.bookId) }
        snapshot.library.progress.filter { it.editionId == editionId }.forEach { add(it.bookId) }
        snapshot.library.bookmarks.filter { it.editionId == editionId }.forEach { add(it.bookId) }
        snapshot.library.readthroughs.filter { it.editionId == editionId }.forEach { add(it.libraryEntryId) }
    }
}

data class ManualWorkMergePreview(
    val participants: List<ManualWorkMergeParticipant>,
    val snapshot: ManualWorkMergeSnapshot,
    val counts: ManualWorkMergeCounts,
    val fingerprint: String,
    val conflicts: List<ManualWorkMergeConflict>,
)

data class ManualWorkMergeParticipant(
    val work: WorkEntity,
    val editions: List<ManualWorkMergeEditionPreview>,
)

data class ManualWorkMergeEditionPreview(
    val bookId: String?,
    val edition: EditionEntity,
    val chapters: List<ChapterEntity>,
    val progress: PlaybackProgressEntity?,
    val bookmarks: List<BookmarkEntity>,
)
