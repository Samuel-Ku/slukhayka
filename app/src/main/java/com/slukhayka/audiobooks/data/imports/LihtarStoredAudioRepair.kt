package com.slukhayka.audiobooks.data.imports

import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.metadata.MetadataAssertions
import com.slukhayka.audiobooks.data.merge.MergeKey
import com.slukhayka.audiobooks.data.source.LihtarAudio
import com.slukhayka.audiobooks.data.source.SourceAdapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Repairs only the proven old Lihtar import: one navigation cue masquerading
 * as the entire book. Other sources/topologies need an explicit repair, not
 * an automatic replacement of the Edition's chapters (ADR-0007).
 */
internal class LihtarStoredAudioRepair(
    private val dao: AudiobookDao,
    private val adapter: SourceAdapter?,
    private val isRefused: () -> Boolean,
    private val memo: AutoRepairMemo = AutoRepairMemo()
) {
    private val mutexes = ConcurrentHashMap<String, Mutex>()

    suspend fun repair(bookId: String) {
        if (adapter == null || isRefused() || !memo.canAttempt(bookId)) return
        if (dao.getSourcesForBookSync(bookId).singleOrNull()?.type != "lihtar") return
        // Resolving this title must not queue playback of an unrelated book.
        mutexes.getOrPut(bookId) { Mutex() }.withLock {
            if (isRefused() || !memo.canAttempt(bookId)) return
            val book = dao.getAudiobookById(bookId) ?: return
            val edition = dao.getEditionForWork(bookId) ?: return
            val source = dao.getSourcesForBookSync(bookId).singleOrNull()
                ?.takeIf { it.type == "lihtar" && it.editionId == edition.id } ?: return
            val chapter = dao.getChaptersListForBook(bookId).singleOrNull()
                ?.takeIf { it.chapterIndex == 0 && it.editionId == edition.id } ?: return
            val track = dao.getTracksForSourceSync(source.id).singleOrNull()
                ?.takeIf { it.trackIndex == 0 && LihtarAudio.isNavigationAudio(it.url) } ?: return
            val page = source.url.toHttpUrlOrNull() ?: return
            if (page.host != "lihtar.in.ua" || !page.encodedPath.startsWith("/biblioteka/")) return
            try {
                val detail = adapter.fetchBookPage(source.url)
                currentCoroutineContext().ensureActive()
                memo.recordFailure(bookId)
                if (isRefused() || detail.url != source.url || detail.chapters.isEmpty() ||
                    MergeKey.normalizeTitle(detail.title) != MergeKey.normalizeTitle(book.title) ||
                    detail.chapters.any { !LihtarAudio.isBookAudio(it.streamUrl) }
                ) return
                val materialized = MetadataAssertions.materializeChaptersAndTracks(
                    edition.id, source.id, bookId, book.title, detail.chapters
                )
                // Keep the existing anchor; bookmarks/favorites are never deleted.
                val chapters = materialized.chapters.mapIndexed { index, row ->
                    if (index == 0) row.copy(id = chapter.id) else row
                }
                val tracks = materialized.tracks.mapIndexed { index, row ->
                    if (index == 0) row.copy(id = track.id) else row
                }
                val duration = MetadataAssertions.normalizeDurationSeconds(detail.totalDurationSeconds)
                    ?: MetadataAssertions.normalizeDurationSeconds(detail.chapters.sumOf { it.durationSeconds })
                    ?: 0L
                if (dao.repairNavigationOnlyChapter(source, edition, chapter, track, chapters, tracks, duration)) {
                    memo.recordSuccess(bookId)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // No partial writes or immediate retry storm after an offline failure.
                memo.recordFailure(bookId)
            }
        }
    }
}
