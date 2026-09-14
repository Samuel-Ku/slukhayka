package com.slukhayka.audiobooks.data.catalog

import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.BookRow
import com.slukhayka.audiobooks.data.source.SourceAccessCandidate
import com.slukhayka.audiobooks.data.source.SourceAccessMode
import com.slukhayka.audiobooks.data.source.SourceAccessPolicy
import com.slukhayka.audiobooks.data.source.sourceIdForUrl
import com.slukhayka.audiobooks.player.SmartRetryPolicy
import com.slukhayka.audiobooks.data.privacy.AudioNoticePolicy
import com.slukhayka.audiobooks.data.source.SourceRegistry
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

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
    private val sameEditionChapters: suspend (String) -> List<List<SourceCatalog.PlayableChapter>> = { emptyList() },
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
        if (chapterCount <= 0 || chapterIndex < 0 || chapterIndex >= chapterCount) return null
        // Edition linkage is stronger evidence than a narrator string. This
        // also works for single-file books and unknown narrators, without a
        // second card or any network request.
        val stored = sameEditionChapters(book.id).mapNotNull { playable ->
            verifiedChapter(playable, chapterCount, chapterIndex, failedSourceId)
        }
        val orderedStored = SourceAccessPolicy.order(stored.map {
            SourceAccessCandidate(it.sourceId, url = it.url, localAvailable = it.localFilePath != null)
        })
        for (entry in orderedStored) {
            val candidate = stored.first { it.sourceId == entry.sourceId && it.url == entry.url }
            if (allowed(candidate.sourceId, failedSourceId)) return candidate
        }
        if (mergeKey.isBlank()) return null
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
            if (!allowed(sourceId, failedSourceId)) continue
            val playable = try {
                chaptersFor(row.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                continue
            }
            // The catalog may select a different physical source than the
            // card URL. Re-check its actual identity and current refusals.
            val candidate = verifiedChapter(playable, chapterCount, chapterIndex, failedSourceId) ?: continue
            return candidate
        }
        return null
    }

    private fun allowed(sourceId: String, failedSourceId: String?): Boolean =
        sourceId.isNotBlank() && sourceId != failedSourceId &&
            sourceId !in refusedSourceIds() && !SourceRegistry.isScam(sourceId) &&
            (sourceId == "local" || SourceAccessPolicy.modeFor(sourceId) == SourceAccessMode.DIRECT)

    private fun verifiedChapter(
        playable: List<SourceCatalog.PlayableChapter>,
        chapterCount: Int,
        chapterIndex: Int,
        failedSourceId: String?,
    ): FallbackChapter? {
        if (playable.size != chapterCount || playable.map { it.chapter.chapterIndex } != (0 until chapterCount).toList()) return null
        val pair = playable[chapterIndex]
        val sourceId = pair.sourceId ?: return null
        if (!allowed(sourceId, failedSourceId)) return null
        val track = pair.track ?: return null
        if (track.trackIndex != chapterIndex) return null
        val local = track.localFilePath?.takeIf { SmartRetryPolicy.localFileReady(it) }
        val remote = track.url.toHttpUrlOrNull()?.takeUnless(AudioNoticePolicy::isBlocked)?.toString()
        val locator = local ?: remote ?: return null
        if (sourceId == "local" && local == null) return null
        return FallbackChapter(locator, sourceId, local)
    }
}
