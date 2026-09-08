package com.slukhayka.audiobooks.data.ingest

import com.slukhayka.audiobooks.data.metadata.SharedBookMetaStore
import com.slukhayka.audiobooks.data.metadata.SubmissionAccessMode
import com.slukhayka.audiobooks.data.metadata.SubmissionChapter
import com.slukhayka.audiobooks.data.metadata.SubmissionPublication

/**
 * ADR-0035 / #605 — the publication gate of ONE listener submission: only a
 * REAL playback verdict ([SubmissionVerification], recorded by the player
 * verdict seam) lets a submission leave the device. A bare link insert — no
 * verdict — publishes NOTHING (spec-601 AC: «голе вставлення посилання без
 * верифікаційного вердикту не публікує нічого»). The payload is assembled
 * from the SAME yt-dlp metadata the import door consumed ([YouTubeSubmissionPlanner]),
 * so the shared document carries the honest observed identity + chapters;
 * the store keys it by the normalized URL, so the same link never
 * duplicates (the mergeKey dedup then happens on every consumer's write
 * path: the same narration lands as a second Source of ONE Work).
 */
class SubmissionPublisher(
    private val sharedStore: SharedBookMetaStore,
    private val verification: SubmissionVerification,
    private val clock: () -> Long = System::currentTimeMillis
) {

    enum class Result {
        /** The verified submission document was handed to the shared store. */
        PUBLISHED,

        /** No real playback verdict yet — nothing was published. */
        NOT_VERIFIED,

        /** The metadata carried no usable identity — nothing was published. */
        METADATA_FAILED
    }

    /**
     * Assembles and publishes one submission document. [sourceId] is the
     * door's Source id — the same key the player verdict seam records the
     * verdict under; [submitterId] is a bounded anonymous device id.
     */
    suspend fun publish(
        url: String,
        metadataJson: String,
        channelId: String,
        sourceId: String,
        submitterId: String
    ): Result {
        if (!verification.isVerified(sourceId)) return Result.NOT_VERIFIED
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
            verifiedAt = verification.verifiedAt(sourceId) ?: return Result.NOT_VERIFIED,
            submittedAt = clock(),
            submitterId = submitterId
        )
        sharedStore.publishSubmission(publication)
        return Result.PUBLISHED
    }
}