package com.slukhayka.audiobooks.data.ingest

import com.slukhayka.audiobooks.data.metadata.SharedBookMetaStore
import com.slukhayka.audiobooks.data.metadata.SubmissionAccessMode
import com.slukhayka.audiobooks.data.metadata.SubmissionChapter
import com.slukhayka.audiobooks.data.metadata.SubmissionPublication

/**
 * ADR-0035 / #605/#607 — the publication door of ONE listener submission.
 * The anti-spam policy ([SubmissionPolicy]) gates every publish: a real
 * playback verdict, the per-device daily budget and the normalized-URL
 * dedup are all enforced BEFORE anything leaves the device; only a verified,
 * budgeted, non-duplicate submission assembles the payload (from the SAME
 * yt-dlp metadata the import door consumed) and lands in the shared base.
 * A refused submission publishes NOTHING and consumes no budget.
 */
class SubmissionPublisher(
    private val sharedStore: SharedBookMetaStore,
    private val policy: SubmissionPolicy,
    private val clock: () -> Long = System::currentTimeMillis
) {

    enum class Result {
        /** The verified, budgeted, non-duplicate document was published. */
        PUBLISHED,

        /** No real playback verdict yet — nothing was published. */
        NOT_VERIFIED,

        /** The metadata carried no usable identity — nothing was published. */
        METADATA_FAILED,

        /** The per-device daily budget is exhausted — nothing was published. */
        DAILY_LIMIT_REACHED,

        /** The normalized URL is already published — nothing was published. */
        ALREADY_PUBLISHED
    }

    /**
     * Assembles and publishes one submission document. [sourceId] is the
     * door's Source id — the same key the player verdict seam records the
     * verdict under; [submitterId] is a bounded anonymous device id (also
     * the daily-budget key).
     */
    suspend fun publish(
        url: String,
        metadataJson: String,
        channelId: String,
        sourceId: String,
        submitterId: String
    ): Result {
        val decision = policy.decide(sourceId, url, submitterId)
        if (!decision.allowed) {
            return when (decision.reason) {
                SubmissionPolicy.Reason.NOT_VERIFIED -> Result.NOT_VERIFIED
                SubmissionPolicy.Reason.DAILY_LIMIT_REACHED -> Result.DAILY_LIMIT_REACHED
                SubmissionPolicy.Reason.ALREADY_PUBLISHED -> Result.ALREADY_PUBLISHED
                null -> Result.NOT_VERIFIED
            }
        }
        val metadata = YouTubeSubmissionPlanner.parseMetadata(metadataJson) ?: return Result.METADATA_FAILED
        val plan = YouTubeSubmissionPlanner.plan(url, metadata, channelId)
        if (plan.title.isBlank()) return Result.METADATA_FAILED

        val publication = SubmissionPublication(
            sourceUrl = url.trim(),
            accessMode = SubmissionAccessMode.YOUTUBE,
            title = plan.title,
            author = plan.author,
            narrator = plan.narrator,
            durationSeconds = metadata.durationSeconds,
            chapters = plan.chapters.map { SubmissionChapter(it.title, it.watchUrl) },
            verifiedAt = policy.verifiedAt(sourceId) ?: return Result.NOT_VERIFIED,
            submittedAt = clock(),
            submitterId = submitterId
        )
        sharedStore.publishSubmission(publication)
        policy.consume(submitterId)
        return Result.PUBLISHED
    }
}