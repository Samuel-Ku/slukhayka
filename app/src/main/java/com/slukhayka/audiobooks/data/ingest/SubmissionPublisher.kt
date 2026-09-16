package com.slukhayka.audiobooks.data.ingest

import com.slukhayka.audiobooks.data.metadata.SharedBookMetaStore
import com.slukhayka.audiobooks.data.metadata.SubmissionAccessMode
import com.slukhayka.audiobooks.data.metadata.SubmissionChapter
import com.slukhayka.audiobooks.data.metadata.SubmissionCandidateFactory
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
        if (!decision.allowed) return refusedResult(decision.reason)
        val result = assembleAndPublish(url, metadataJson, channelId, sourceId, submitterId)
        // A published listener submission spends one daily-budget slot.
        if (result == Result.PUBLISHED) policy.consume(submitterId)
        return result
    }

    /**
     * The curator-batch door (ADR-0035 п. 12 / #608 — the seeder mode): the
     * SAME assembly and store write as [publish], gated by
     * [SubmissionPolicy.decideCurator] — the verdict and the URL dedup stay,
     * the daily limit does not exist for the curator. The daily budget is
     * never consumed here.
     */
    suspend fun publishCurator(
        url: String,
        metadataJson: String,
        channelId: String,
        sourceId: String,
        submitterId: String
    ): Result {
        val decision = policy.decideCurator(sourceId, url)
        if (!decision.allowed) return refusedResult(decision.reason)
        return assembleAndPublish(url, metadataJson, channelId, sourceId, submitterId)
    }

    private fun refusedResult(reason: SubmissionPolicy.Reason?): Result = when (reason) {
        SubmissionPolicy.Reason.NOT_VERIFIED -> Result.NOT_VERIFIED
        SubmissionPolicy.Reason.DAILY_LIMIT_REACHED -> Result.DAILY_LIMIT_REACHED
        SubmissionPolicy.Reason.ALREADY_PUBLISHED -> Result.ALREADY_PUBLISHED
        null -> Result.NOT_VERIFIED
    }

    private suspend fun assembleAndPublish(
        url: String,
        metadataJson: String,
        channelId: String,
        sourceId: String,
        submitterId: String,
        verifiedAtOverride: Long? = null
    ): Result {
        val metadata = YouTubeSubmissionPlanner.parseMetadata(metadataJson) ?: return Result.METADATA_FAILED
        val plan = YouTubeSubmissionPlanner.plan(url, metadata, channelId)
        if (plan.title.isBlank()) return Result.METADATA_FAILED

        // Moderation T1 (#834) — a verified submission becomes a CANDIDATE in
        // pending_submissions; only the curator's bot writes catalog_cards.
        val verifiedAt = verifiedAtOverride
            ?: policy.verifiedAt(sourceId)
            ?: return Result.NOT_VERIFIED
        val canonical = SubmissionUrlCanonicalizer.canonicalOrSelf(url)
        // The queue is now the dedup source: the same canonical link is one
        // candidate, and a repeat is the friendly ALREADY_PUBLISHED state.
        if (sharedStore.getCandidate(canonical) != null) return Result.ALREADY_PUBLISHED
        val candidate = SubmissionCandidateFactory.create(
            url = url,
            canonical = canonical,
            title = plan.title,
            uid = submitterId,
            playedAt = verifiedAt,
            createdAt = clock(),
            author = plan.author,
            narrator = plan.narrator,
            durationSeconds = metadata.durationSeconds,
            chaptersCount = plan.chapters.size,
            sourceId = sourceId,
            metadataJson = metadataJson
        ) ?: return Result.METADATA_FAILED
        return if (sharedStore.enqueueCandidate(candidate)) Result.PUBLISHED else Result.METADATA_FAILED
    }

    /**
     * Spec-53 T12 — publishes a submission whose REAL playback verdict was
     * recorded on an earlier day, when the daily budget had run out. The
     * verdict proof rides in from the stored row ([verifiedAt] is the moment
     * that row was written, i.e. the playing event): the in-app verification
     * record is honestly gone after a restart, while the state row is not.
     *
     * The limit is RESPECTED, never bypassed: the budget is re-read here, and
     * a still-exhausted day returns [Result.DAILY_LIMIT_REACHED] with nothing
     * written. The URL dedup is checked too — another device may have
     * published the same link in the meantime.
     */
    suspend fun publishDeferred(
        url: String,
        metadataJson: String,
        channelId: String,
        sourceId: String,
        submitterId: String,
        verifiedAt: Long
    ): Result {
        if (policy.remainingToday(submitterId) <= 0) return Result.DAILY_LIMIT_REACHED
        if (sharedStore.getSubmission(url.trim()) != null) return Result.ALREADY_PUBLISHED

        val result = assembleAndPublish(
            url = url,
            metadataJson = metadataJson,
            channelId = channelId,
            sourceId = sourceId,
            submitterId = submitterId,
            verifiedAtOverride = verifiedAt
        )
        // A publication really made on this day spends this day's slot.
        if (result == Result.PUBLISHED) policy.consume(submitterId)
        return result
    }

    /**
     * Spec-53 T7 — corrects the metadata of a publication that is ALREADY in
     * the shared base. The verdict gate is the CALLER's: [ListenerSubmissionFlow]
     * only reaches this door for a stored row in [SubmissionState.State.PUBLISHED],
     * a state that could only be written after a real playback verdict — so the
     * quality bar survives process restarts, when the in-memory verification
     * record is honestly gone. The URL dedup is deliberately not consulted:
     * this is the same document being fixed, not a new submission.
     */
    suspend fun updatePublishedMetadata(
        url: String,
        metadataJson: String,
        channelId: String,
        sourceId: String,
        submitterId: String,
        title: String,
        author: String?,
        narrator: String?,
        verifiedAt: Long
    ): Result {
        if (title.isBlank()) return Result.METADATA_FAILED
        val metadata = YouTubeSubmissionPlanner.parseMetadata(metadataJson) ?: return Result.METADATA_FAILED
        val plan = YouTubeSubmissionPlanner.plan(url, metadata, channelId)

        sharedStore.publishSubmission(
            SubmissionPublication(
                sourceUrl = url.trim(),
                accessMode = SubmissionAccessMode.YOUTUBE,
                title = title.trim(),
                author = author?.trim()?.takeIf { it.isNotBlank() },
                narrator = narrator?.trim()?.takeIf { it.isNotBlank() },
                durationSeconds = metadata.durationSeconds,
                chapters = plan.chapters.map { SubmissionChapter(it.title, it.watchUrl) },
                verifiedAt = verifiedAt,
                submittedAt = clock(),
                submitterId = submitterId
            )
        )
        return Result.PUBLISHED
    }

    /**
     * The TG door (ADR-0035 п. 13 / #606) — publishes a RED-prototype TG
     * post as METADATA-ONLY. The identity claims are the adapter's
     * captured-page parse (TitleNormalizer output — the author is never
     * invented); the payload carries [SubmissionAccessMode.TG_PREVIEW] and
     * NO chapters, so other installs materialize the Work honestly and
     * playback surfaces the absent source as unavailable, never fabricated.
     * No playback verdict is POSSIBLE (the preview exposes no audio), so
     * the anti-spam is the policy's daily budget + URL dedup, and the parse
     * itself is the quality bar; [SubmissionPublication.verifiedAt] is
     * honestly 0 — no verdict moment ever fired.
     */
    suspend fun publishTgMetadata(
        url: String,
        title: String,
        author: String?,
        narrator: String?,
        coverUrl: String?,
        description: String?,
        submitterId: String
    ): Result {
        val decision = policy.decideMetadataOnly(url, submitterId)
        if (!decision.allowed) {
            return when (decision.reason) {
                SubmissionPolicy.Reason.DAILY_LIMIT_REACHED -> Result.DAILY_LIMIT_REACHED
                SubmissionPolicy.Reason.ALREADY_PUBLISHED -> Result.ALREADY_PUBLISHED
                else -> Result.NOT_VERIFIED
            }
        }
        if (title.isBlank()) return Result.METADATA_FAILED

        sharedStore.publishSubmission(
            SubmissionPublication(
                sourceUrl = url.trim(),
                accessMode = SubmissionAccessMode.TG_PREVIEW,
                title = title.trim(),
                author = author,
                narrator = narrator,
                description = description,
                coverUrl = coverUrl,
                durationSeconds = null,
                chapters = emptyList(),
                verifiedAt = 0L,
                submittedAt = clock(),
                submitterId = submitterId
            )
        )
        policy.consume(submitterId)
        return Result.PUBLISHED
    }
}
