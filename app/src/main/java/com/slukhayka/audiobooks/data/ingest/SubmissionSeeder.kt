package com.slukhayka.audiobooks.data.ingest

import com.slukhayka.audiobooks.data.privacy.PacingPolicy
import com.slukhayka.audiobooks.data.source.YouTubeTracks
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * ADR-0035 п. 12 / #608 — the curator's SEEDER MODE: the same submission
 * mechanism, batch-shaped on the curator's device. Walks a channel or
 * playlist (`-J --flat-playlist` metadata), and for each entry publishes it
 * through the SAME publication door ([SubmissionPublisher.publishCurator]) —
 * the verdict gate, the URL dedup and the shared-base write are identical to
 * a listener submission; the ONLY differences the ADR names are throughput
 * and the absent daily limit.
 *
 * Honest by construction:
 *  - **verdict per item** — an entry without a real playback verdict is
 *    skipped ([SubmissionPublisher.Result.NOT_VERIFIED]); the seeder never
 *    invents a verdict. [itemSourceId] is the deterministic verdict key the
 *    player verdict seam records under;
 *  - **URL dedup** — an already-published entry is skipped without touching
 *    the store (counted honestly);
 *  - **human rhythm** — every fetch first waits the [PacingPolicy] pause and
 *    then a burst slot on the domain (the OfflineDownloads precedent,
 *    spec-38 T5); a refusal costs another policy pause;
 *  - **cancellation is honest** — a cancelled batch stops at the next
 *    [kotlinx.coroutines.ensureActive] checkpoint; the per-item all-or-nothing
 *    publish means the unprocessed half never leaves a partial trace in the
 *    shared base (ADR-0035 п. 12 AC4).
 *
 * Pure JVM: the metadata fetchers, the pause and the clock are injected
 * seams (the FeedCursorTest / PacingPolicyTest precedent — deterministic
 * tests without sleeping or binaries).
 */
class SubmissionSeeder(
    private val publisher: SubmissionPublisher,
    private val pacing: PacingPolicy,
    private val fetchFlatMetadata: suspend (String) -> String?,
    private val fetchItemMetadata: suspend (String) -> String?,
    private val pauseMillis: suspend (Long) -> Unit = { delay(it) },
    private val nowMillis: () -> Long = System::currentTimeMillis
) {

    /** The honest tally of one seed run. */
    data class SeedResult(
        /** Entries walked (bounded by [maxItems]). */
        val walked: Int,
        /** Published to the shared base (verified + budget-free). */
        val published: Int,
        /** Skipped — the normalized URL is already in the shared base. */
        val alreadyPublished: Int,
        /** Skipped — no real playback verdict for the item (the main barrier). */
        val notVerified: Int,
        /** Skipped — no fetchable metadata or no usable identity. */
        val metadataFailed: Int
    ) {
        val skipped: Int get() = alreadyPublished + notVerified + metadataFailed
    }

    /**
     * Walks [channelUrl] (a channel or a playlist) and publishes each entry
     * through the curator door. [channelId] is the `@channel` key
     * [TitleNormalizer] cuts channel marks with; [submitterId] is the
     * bounded anonymous curator device id the documents carry.
     */
    suspend fun seedChannel(
        channelUrl: String,
        channelId: String,
        submitterId: String,
        maxItems: Int = DEFAULT_MAX_ITEMS
    ): SeedResult = withContext(Dispatchers.IO) {
        val counters = Counters()
        currentCoroutineContext().ensureActive()
        waitForBurstSlot()
        val flatJson = fetchFlatMetadata(channelUrl) ?: return@withContext counters.result()
        val metadata = YouTubeSubmissionPlanner.parseMetadata(flatJson) ?: return@withContext counters.result()

        metadata.entries.take(maxItems).forEach { entry ->
            counters.walked++
            // The canonical watch URL — short forms and bare ids resolve the
            // same way the planner resolves them (never a fabricated link).
            val watchUrl = watchUrlOf(entry.url, entry.id)
            if (watchUrl == null) {
                counters.metadataFailed++
                return@forEach
            }
            // Honest cancellation: a stopped batch publishes nothing more.
            currentCoroutineContext().ensureActive()
            waitForBurstSlot()
            val itemJson = fetchItemMetadata(watchUrl)
            if (itemJson == null) {
                counters.metadataFailed++
                return@forEach
            }
            when (publisher.publishCurator(watchUrl, itemJson, channelId, itemSourceId(watchUrl), submitterId)) {
                SubmissionPublisher.Result.PUBLISHED -> counters.published++
                SubmissionPublisher.Result.ALREADY_PUBLISHED -> counters.alreadyPublished++
                SubmissionPublisher.Result.NOT_VERIFIED -> counters.notVerified++
                // METADATA_FAILED (and the unreachable DAILY_LIMIT_REACHED).
                else -> counters.metadataFailed++
            }
        }
        counters.result()
    }

    /**
     * The deterministic verdict key of one seeded item — the contract the
     * player verdict seam records under: URL-derived, stable across runs, so
     * a re-seed recognises an earlier verdict without re-playing the book.
     */
    fun itemSourceId(watchUrl: String): String =
        "seed-youtube-${Integer.toHexString(watchUrl.trim().hashCode())}"

    /** Normalizes an entry link/id to the canonical watch URL (the planner's rule). */
    private fun watchUrlOf(url: String?, id: String?): String? {
        if (!url.isNullOrBlank()) {
            if (YouTubeTracks.isYouTubeWatchUrl(url)) return url
            val shortId = YOUTU_BE_ID.matchEntire(url)?.groupValues?.get(1)
            if (shortId != null) return YouTubeTracks.watchUrlOf(shortId)
        }
        return id?.takeIf { it.isNotBlank() }?.let(YouTubeTracks::watchUrlOf)
    }

    /** Spec-38 T5: pause first, then ask the policy for a burst slot. */
    private suspend fun waitForBurstSlot() {
        pauseMillis(pacing.nextPauseMillis())
        while (!pacing.allowsRequest(SEED_DOMAIN, nowMillis())) {
            pauseMillis(pacing.nextPauseMillis())
        }
    }

    private class Counters {
        var walked = 0
        var published = 0
        var alreadyPublished = 0
        var notVerified = 0
        var metadataFailed = 0

        fun result() = SeedResult(walked, published, alreadyPublished, notVerified, metadataFailed)
    }

    private companion object {
        /** The pacing burst gate applies to the one domain all yt-dlp calls share. */
        const val SEED_DOMAIN = "youtube.com"

        /** The honest bound of one seed run — a channel is walked in batches. */
        const val DEFAULT_MAX_ITEMS = 100

        private val YOUTU_BE_ID = Regex("""https?://youtu\.be/([A-Za-z0-9_-]+).*""")
    }
}