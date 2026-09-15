package com.slukhayka.audiobooks.data.ingest

import com.slukhayka.audiobooks.data.collections.MiniJson
import com.slukhayka.audiobooks.data.source.YtDlpStreamExtractor
import com.slukhayka.audiobooks.data.source.YouTubeTracks

/**
 * ADR-0035 / #604 — the pure-JVM plan of ONE submitted YouTube link (single
 * video or playlist) from its yt-dlp `-J` metadata.
 *
 *  - **Work identity** comes from [TitleNormalizer] on the video/playlist
 *    title (author never invented, channel marks cut); the write path then
 *    merges on the same [com.slukhayka.audiobooks.data.merge.MergeKey] as
 *    every other source.
 *  - **Chapters come from OBSERVED boundaries only** (ADR-0014): playlist
 *    entries are the observed chapter list; a single video has no observed
 *    boundaries and stays ONE whole-file chapter. Nothing is fabricated.
 *  - **Track URLs are normalized to watch URLs** so the existing
 *    [com.slukhayka.audiobooks.data.source.YouTubeStreamResolver] seam and
 *    the shared download loop drive streaming and offline playback exactly
 *    like the 4read-embed path (v1.3.6) — the signed stream URL is resolved
 *    per use, never persisted (ADR-0007: the Source owns physical tracks).
 *
 * No Android, no network, no database — fixture-tested.
 */
object YouTubeSubmissionPlanner {

    /** One chapter of the submitted book: a display title and a watch URL. */
    data class SubmittedChapter(val title: String, val watchUrl: String, val durationSeconds: Long = 0L)

    /** The import plan: identity + the observed chapter list. */
    data class SubmissionPlan(
        val title: String,
        val author: String?,
        val narrator: String?,
        val sourceUrl: String,
        val chapters: List<SubmittedChapter>,
        /**
         * Spec-53 (T9 follow-up) — the uploader/channel the engine observed.
         * Provenance for the book's description; null when the engine saw
         * none, and then the description says only where the book came from.
         */
        val channelName: String? = null
    )

    /** One playlist entry as yt-dlp's flat-playlist JSON carries it. */
    data class MetadataEntry(
        val id: String?,
        val url: String?,
        val title: String?,
        /** Spec-53 T2 — the REAL entry duration the engine observed, or null. */
        val durationSeconds: Long? = null
    )

    /** The parsed `-J` document: title plus entries (empty = single video). */
    data class Metadata(
        val id: String?,
        val title: String,
        val entries: List<MetadataEntry>,
        /**
         * ADR-0035 / #605 — the total duration the metadata observes for a
         * SINGLE video (playlists carry per-entry durations, never an honest
         * total — so playlists stay null). A metadata delta, not a canonical
         * fact; null when absent/implausible.
         */
        val durationSeconds: Long? = null,
        /**
         * Spec-53 T9 — the observed cover for the pre-add preview (yt-dlp's
         * `thumbnail`, NewPipe's widest thumbnail); null when the engine saw
         * none — never a placeholder.
         */
        val coverUrl: String? = null,
        /** Spec-53 (T9 follow-up) — the uploader/channel name, for provenance. */
        val uploader: String? = null
    )

    /**
     * Parses the yt-dlp `-J` metadata (single video: no `entries`; playlist:
     * `entries[]` in order). Null when the document carries no usable title —
     * the caller reports the honest failure.
     */
    fun parseMetadata(ytDlpJson: String): Metadata? {
        val root = MiniJson.parse(YtDlpStreamExtractor.stripNullFields(ytDlpJson)) as? Map<*, *>
            ?: return null
        val title = (root["title"] as? String)?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        val entries = (root["entries"] as? List<*>)?.mapNotNull { item ->
            val entry = item as? Map<*, *> ?: return@mapNotNull null
            MetadataEntry(
                id = entry["id"] as? String,
                url = entry["url"] as? String,
                title = entry["title"] as? String,
                // Spec-53 T2 — per-entry durations from either engine (yt-dlp
                // and NewPipe both emit seconds here); plausibility-bounded.
                durationSeconds = (entry["duration"] as? Number)?.toLong()
                    ?.takeIf { it > 0 && it <= MAX_PLAUSIBLE_DURATION_SECONDS }
            )
        } ?: emptyList()
        val durationSeconds = if (entries.isEmpty()) {
            (root["duration"] as? Number)?.toLong()
                ?.takeIf { it > 0 && it <= MAX_PLAUSIBLE_DURATION_SECONDS }
        } else {
            null
        }
        return Metadata(
            id = root["id"] as? String,
            title = title,
            entries = entries,
            durationSeconds = durationSeconds,
            coverUrl = (root["thumbnail"] as? String)?.trim()?.takeIf { it.isNotBlank() },
            uploader = (root["uploader"] as? String)?.trim()?.takeIf { it.isNotBlank() }
        )
    }

    /** The plausible ceiling of an observed single-video duration: 100 hours. */
    private const val MAX_PLAUSIBLE_DURATION_SECONDS = 100L * 60 * 60

    /**
     * Builds the submission plan from the submitted URL and its metadata.
     * A playlist → one chapter per playable entry (observed boundaries); a
     * single video → one whole-file chapter. An entry with neither a watch
     * URL nor an id is skipped — never fabricated.
     *
     * Spec-53 T11 — [selectedWatchUrls] narrows a big playlist to the picked
     * positions: non-null keeps ONLY the entries whose canonical watch URL is
     * in the set, so an unselected position never becomes a chapter (and the
     * write path never creates a track for it). Chapter numbering keeps the
     * entry's ORIGINAL position — "Розділ 7" stays the seventh, not the
     * second after filtering.
     */
    fun plan(
        sourceUrl: String,
        metadata: Metadata,
        channelId: String,
        selectedWatchUrls: Set<String>? = null
    ): SubmissionPlan {
        // Spec-53 (T9 follow-up) — a channel-post pattern wins when it
        // declares an author; otherwise a single-line YouTube title gets the
        // video-shape parse, which drops the promo tail («| Audiobook …») and
        // reads the author from «| Автор», «by Author» or «Автор — Назва».
        // Never invented: with no provable name the raw line stays the title.
        val caption = TitleNormalizer.parse(metadata.title, channelId)
        val identity = if (caption.author == null && !metadata.title.contains('\n')) {
            val video = TitleNormalizer.parseVideoTitle(metadata.title)
            if (video.author != null || video.title != metadata.title) video else caption
        } else {
            caption
        }
        val title = identity.title.ifBlank { metadata.title }
        val chapters = if (metadata.entries.isNotEmpty()) {
            metadata.entries.mapIndexedNotNull { index, entry ->
                val watchUrl = watchUrlOf(entry.url, entry.id) ?: return@mapIndexedNotNull null
                if (selectedWatchUrls != null && watchUrl !in selectedWatchUrls) {
                    return@mapIndexedNotNull null
                }
                SubmittedChapter(
                    title = entry.title?.trim()?.takeIf { it.isNotBlank() } ?: "Розділ ${index + 1}",
                    watchUrl = watchUrl,
                    durationSeconds = entry.durationSeconds ?: 0L
                )
            }
        } else {
            val watchUrl = watchUrlOf(sourceUrl, metadata.id) ?: return SubmissionPlan(
                title = title,
                author = identity.author,
                narrator = identity.narrator,
                sourceUrl = sourceUrl,
                chapters = emptyList(),
                channelName = metadata.uploader
            )
            listOf(SubmittedChapter(title = title, watchUrl = watchUrl, durationSeconds = metadata.durationSeconds ?: 0L))
        }
        return SubmissionPlan(
            title = title,
            author = identity.author,
            narrator = identity.narrator,
            sourceUrl = sourceUrl,
            chapters = chapters,
            channelName = metadata.uploader
        )
    }

    /** Normalizes a submitted URL or entry to the canonical watch URL. */
    private fun watchUrlOf(url: String?, id: String?): String? {
        if (!url.isNullOrBlank()) {
            if (YouTubeTracks.isYouTubeWatchUrl(url)) return url
            // Short form: https://youtu.be/<id> — extract the id honestly.
            val shortId = YOUTU_BE_ID.matchEntire(url)?.groupValues?.get(1)
            if (shortId != null) return YouTubeTracks.watchUrlOf(shortId)
        }
        return id?.takeIf { it.isNotBlank() }?.let(YouTubeTracks::watchUrlOf)
    }

    private val YOUTU_BE_ID = Regex("""https?://youtu\.be/([A-Za-z0-9_-]+).*""")
}