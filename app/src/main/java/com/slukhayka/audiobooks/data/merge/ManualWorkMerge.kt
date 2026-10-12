package com.slukhayka.audiobooks.data.merge

import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.BookmarkEntity
import com.slukhayka.audiobooks.data.db.ChapterEntity
import com.slukhayka.audiobooks.data.db.EditionEntity
import com.slukhayka.audiobooks.data.db.PlaybackProgressEntity
import com.slukhayka.audiobooks.data.db.WorkEntity
import java.util.Collections
import kotlinx.coroutines.flow.first

/**
 * The initial read-only preview of two Works' library and catalog narrations (#968).
 * Materializes their separate listening state without approving or applying
 * a merge. A preview is not an approved transaction plan.
 */
class ManualWorkMerge(private val dao: AudiobookDao) {
    suspend fun prepareManualWorkMerge(a: String, b: String): ManualWorkMergePreview {
        val entries = dao.observeLibraryEntries().first()
        val editions = dao.observeEditions().first()
        val participants = listOf(a, b).map { workId ->
            val work = requireNotNull(dao.getWorkById(workId)) { "Work is unavailable: $workId" }
            val bookIds = entries.filter { it.workId == workId }.map { it.id }.toSet()
            val narrations = editions.filter { it.workId == workId || it.workId in bookIds }.map { edition ->
                ManualWorkMergeEditionPreview(
                    bookId = edition.workId.takeIf { it in bookIds },
                    edition = edition,
                    chapters = immutableList(dao.getChaptersListForEdition(edition.id)),
                    progress = dao.getPlaybackProgressSyncByEdition(edition.id),
                    bookmarks = immutableList(dao.getBookmarksForEdition(edition.id).first()),
                )
            }
            ManualWorkMergeParticipant(work, immutableList(narrations))
        }
        return ManualWorkMergePreview(immutableList(participants))
    }
}

data class ManualWorkMergePreview(val participants: List<ManualWorkMergeParticipant>)

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

private fun <T> immutableList(items: List<T>): List<T> =
    Collections.unmodifiableList(items.toList())
