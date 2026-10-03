package com.slukhayka.audiobooks.data.imports

import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.ChapterEntity
import com.slukhayka.audiobooks.data.db.SourceTrackEntity
import java.util.Base64

/** A listener's display order; persisted anchors and provider indices remain in original order. */
enum class ChapterReorderResult { APPLIED, UNCHANGED, STALE, INVALID_ORDER, INVALID_TRACKS }

internal object ChapterOrder {
    fun key(bookId: String) = "chapter-order:$bookId"
    fun encode(ids: List<String>): String = ids.joinToString(".") {
        Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray(Charsets.UTF_8))
    }
    fun decode(value: String): List<String>? = runCatching {
        value.split('.').map { String(Base64.getUrlDecoder().decode(it), Charsets.UTF_8) }
            .takeIf { ids -> ids.all { it.isNotBlank() } && ids.distinct().size == ids.size }
    }.getOrNull()

    suspend fun originalIds(dao: AudiobookDao, bookId: String, chapters: List<ChapterEntity>): List<String>? {
        val correction = dao.getCorrectionsForMergeKey(key(bookId)).firstOrNull { it.kind == "FIELD" }
        return originalIds(chapters, correction?.value)
    }

    fun originalIds(chapters: List<ChapterEntity>, value: String?): List<String>? {
        val ids = chapters.map { it.id }
        if (value == null) return ids
        val original = decode(value) ?: return null
        if (!ids.toSet().containsAll(original)) return null
        val known = original.toSet()
        return original + ids.filter { it !in known }
    }

    /** One mapping per book and emission, shared by every bookmark or progress row. */
    fun displayedIndices(chapters: List<ChapterEntity>, value: String?): List<Int>? {
        val original = originalIds(chapters, value) ?: return null
        val indices = chapters.associate { it.id to it.chapterIndex }
        return original.map { indices.getValue(it) }
    }

    /** Attach a newly observed Source in the original provider order. */
    suspend fun projectTracks(dao: AudiobookDao, bookId: String, tracks: List<SourceTrackEntity>): List<SourceTrackEntity> {
        val chapters = dao.getChaptersListForBook(bookId)
        val original = originalIds(dao, bookId, chapters) ?: error("Invalid chapter order memory")
        val displayed = chapters.map { it.id }
        return tracks.map { track ->
            val id = original.getOrNull(track.trackIndex)
            val index = id?.let { displayed.indexOf(it) }?.takeIf { it >= 0 } ?: track.trackIndex
            track.copy(trackIndex = index)
        }
    }

    data class ChapterAnchor(val chapterId: String?, val displayedIndex: Int)

    suspend fun resolveAnchor(dao: AudiobookDao, bookId: String, originalIndex: Int): ChapterAnchor {
        val rows = dao.getChapterOrderRows(bookId)
        val chapters = rows.map { it.chapter }
        val original = originalIds(chapters, rows.firstOrNull()?.orderMemory)
        val id = original?.getOrNull(originalIndex)
        val index = chapters.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: originalIndex
        return ChapterAnchor(id, index)
    }

    suspend fun anchorProgress(dao: AudiobookDao, row: com.slukhayka.audiobooks.data.db.PlaybackProgressEntity): com.slukhayka.audiobooks.data.listening.AnchoredProgress {
        val anchor = resolveAnchor(dao, row.bookId, row.currentChapterIndex)
        return com.slukhayka.audiobooks.data.listening.AnchoredProgress(row.copy(currentChapterIndex = anchor.displayedIndex), anchor.chapterId)
    }

    suspend fun displayedIndex(dao: AudiobookDao, bookId: String, anchorIndex: Int): Int =
        resolveAnchor(dao, bookId, anchorIndex).displayedIndex

    suspend fun anchorIndexForChapter(dao: AudiobookDao, bookId: String, chapterId: String): Int? {
        val rows = dao.getChapterOrderRows(bookId)
        return originalIds(rows.map { it.chapter }, rows.firstOrNull()?.orderMemory)?.indexOf(chapterId)?.takeIf { it >= 0 }
    }

    suspend fun anchorIndex(dao: AudiobookDao, bookId: String, displayIndex: Int): Int {
        val rows = dao.getChapterOrderRows(bookId)
        val chapters = rows.map { it.chapter }
        val original = originalIds(chapters, rows.firstOrNull()?.orderMemory) ?: return displayIndex
        return chapters.getOrNull(displayIndex)?.id?.let { original.indexOf(it) }
            ?.takeIf { it >= 0 } ?: displayIndex
    }
}
