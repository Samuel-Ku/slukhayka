package com.slukhayka.audiobooks.data.catalog

import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.BookRow
import com.slukhayka.audiobooks.data.source.SourceAccessCandidate
import com.slukhayka.audiobooks.data.source.SourceAccessMode
import com.slukhayka.audiobooks.data.source.SourceAccessPolicy
import com.slukhayka.audiobooks.data.source.sourceIdForUrl
import com.slukhayka.audiobooks.player.SmartRetryPolicy

/**
 * #504 — the same-narration direct fallback for a dead chapter. When the
 * player proves a chapter's stream dead on the remote side (403/404), this
 * resolver finds the SAME chapter index in a sibling Edition that satisfies
 * the ticket's hard safety rule: the SAME real narration (both claims
 * normalized — a blank stored narrator is UNKNOWN, never evidence) AND the
 * SAME chapter count, from a DIRECT source, ordered by the #465 access
 * order. A different narration or a different chapter slicing returns null
 * — a blind index-to-index swap would play someone else's chapter.
 *
 * Boundedness (ADR-0019): chapter-list resolutions are network calls, so at
 * most [maxResolutions] siblings are resolved per lookup and the FIRST
 * verified match wins — never a crawl. Siblings whose persisted
 * [BookRow.totalChapters] already contradicts the current count are skipped
 * WITHOUT resolving; a zero/unknown persisted count resolves and verifies
 * against the real list. The caller's per-chapter single-swap budget
 * ([PlaybackFallbackPolicy]) sits above this: one lookup, one swap.
 *
 * Everything behind seams ([allBooks], [chaptersFor]) so JVM tests pin the
 * matching rule with fakes — no Room, no network.
 */
class PlaybackFallbackResolver(
    private val allBooks: suspend () -> List<BookRow>,
    private val chaptersFor: suspend (String) -> List<SourceCatalog.PlayableChapter>,
    private val refusedSourceIds: () -> Set<String> = { emptySet() },
    private val maxResolutions: Int = 3,
) {

    /** A verified same-narration, same-slicing chapter locator. */
    data class FallbackChapter(
        val url: String,
        val sourceId: String,
        val localFilePath: String? = null,
    )

    /**
     * The verified fallback for ([book], [chapterIndex]), or null when no
     * sibling proves the same narration AND the same chapter count. Never
     * throws: a failing resolve is a skipped candidate, not a crash.
     */
    suspend fun resolve(
        book: AudiobookEntity,
        chapterCount: Int,
        chapterIndex: Int,
        failedSourceId: String?,
    ): FallbackChapter? {
        val mergeKey = book.mergeKey
        if (mergeKey.isBlank() || chapterCount <= 0 || chapterIndex < 0 || chapterIndex >= chapterCount) return null
        val refused = refusedSourceIds()
        val candidates = allBooks()
            .filter { it.id != book.id && it.mergeKey == mergeKey }
            .mapNotNull { row ->
                val sourceId = sourceIdForUrl(row.sourceUrl)
                if (sourceId.isBlank() || sourceId == failedSourceId || sourceId in refused) return@mapNotNull null
                if (SourceAccessPolicy.modeFor(sourceId) != SourceAccessMode.DIRECT) return@mapNotNull null
                if (!SmartRetryPolicy.isSameNarration(book.narrator, row.narrator)) return@mapNotNull null
                row to sourceId
            }
            .takeIf { it.isNotEmpty() } ?: return null
        // #465 order: soundbooks → sluhayua → audiobookmp3 → lihtar, then rest.
        val ordered = SourceAccessPolicy.order(candidates.map { (_, sourceId) -> SourceAccessCandidate(sourceId) })
            .mapNotNull { candidate -> candidates.firstOrNull { (_, sourceId) -> sourceId == candidate.sourceId } }
        var resolutions = 0
        for ((row, sourceId) in ordered) {
            // Persisted count already contradicts — skip without a request.
            if (row.totalChapters > 0 && row.totalChapters != chapterCount) continue
            if (resolutions >= maxResolutions) break
            resolutions++
            val playable = runCatching { chaptersFor(row.id) }.getOrNull() ?: continue
            if (playable.size != chapterCount) continue
            val track = playable.getOrNull(chapterIndex)?.track ?: continue
            val local = track.localFilePath?.takeIf { SmartRetryPolicy.localFileReady(it) }
            val remote = track.url?.takeIf { it.startsWith("http", ignoreCase = true) }
            val locator = local ?: remote ?: continue
            return FallbackChapter(url = locator, sourceId = sourceId, localFilePath = local)
        }
        return null
    }
}
