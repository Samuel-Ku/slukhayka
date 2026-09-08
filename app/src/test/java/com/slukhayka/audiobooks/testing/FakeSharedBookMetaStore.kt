package com.slukhayka.audiobooks.testing

import com.slukhayka.audiobooks.data.metadata.BookProfile
import com.slukhayka.audiobooks.data.metadata.CoverProvenance
import com.slukhayka.audiobooks.data.metadata.DurationProvenance
import com.slukhayka.audiobooks.data.metadata.FacetAssertion
import com.slukhayka.audiobooks.data.metadata.FacetAssertionCodec
import com.slukhayka.audiobooks.data.metadata.FacetAssertionKey
import com.slukhayka.audiobooks.data.metadata.FacetCursor
import com.slukhayka.audiobooks.data.metadata.FacetPage
import com.slukhayka.audiobooks.data.metadata.FacetPageLimits
import com.slukhayka.audiobooks.data.metadata.ProfileProvenance
import com.slukhayka.audiobooks.data.metadata.SharedBookMetaStore
import com.slukhayka.audiobooks.data.metadata.SharedProfileEntry
import com.slukhayka.audiobooks.data.metadata.SharedTombstone
import com.slukhayka.audiobooks.data.metadata.SharedTombstoneCodec
import com.slukhayka.audiobooks.data.metadata.SharedTombstoneCursor
import com.slukhayka.audiobooks.data.metadata.SharedTombstonePage
import com.slukhayka.audiobooks.data.metadata.SharedTombstonePageLimits
import com.slukhayka.audiobooks.data.metadata.SubmissionCursor
import com.slukhayka.audiobooks.data.metadata.SubmissionPage
import com.slukhayka.audiobooks.data.metadata.SubmissionPageLimits
import com.slukhayka.audiobooks.data.metadata.SubmissionPublication
import com.slukhayka.audiobooks.data.metadata.SubmissionPublicationCodec

/**
 * A recording in-memory [SharedBookMetaStore] for tests: duration puts are
 * recorded (and can be made to throw, to prove best-effort write-back), the
 * profile/cover surfaces are inert stubs.
 */
class FakeSharedBookMetaStore(
    var throwOnPut: Boolean = false,
    var throwOnSubmissionPage: Boolean = false,
    var throwOnTombstonePage: Boolean = false
) : SharedBookMetaStore {

    private val facets = linkedMapOf<String, FacetAssertion>()

    override suspend fun getFacet(key: FacetAssertionKey): FacetAssertion? = facets[key.documentId]

    override suspend fun putFacet(assertion: FacetAssertion) {
        if (FacetAssertionCodec.toMap(assertion) == null) return
        val existing = facets[assertion.documentId]
        if (existing == null || assertion.updatedAt > existing.updatedAt) {
            facets[assertion.documentId] = assertion
        }
    }

    override suspend fun getFacetPage(after: FacetCursor?, limit: Int): FacetPage {
        val boundedLimit = FacetPageLimits.bounded(limit)
        if (boundedLimit == 0) return FacetPage(emptyList(), null)
        val ordered = facets.values.sortedWith(compareBy<FacetAssertion> { it.updatedAt }.thenBy { it.documentId })
        val remaining = ordered.filter { assertion ->
            after == null || assertion.updatedAt > after.updatedAt ||
                (assertion.updatedAt == after.updatedAt && assertion.documentId > after.documentId)
        }
        val assertions = remaining.take(boundedLimit)
        val nextCursor = assertions.lastOrNull()
            ?.let { FacetCursor(it.updatedAt, it.documentId) }
        return FacetPage(assertions, nextCursor)
    }

    val durationPuts = mutableListOf<Triple<String, Long, DurationProvenance>>()

    override suspend fun getDuration(editionId: String): Long? = null
    override suspend fun getDurations(editionIds: List<String>): Map<String, Long> = emptyMap()

    override suspend fun putDuration(editionId: String, durationSeconds: Long, provenance: DurationProvenance) {
        if (throwOnPut) throw IllegalStateException("shared base down")
        durationPuts += Triple(editionId, durationSeconds, provenance)
    }

    override suspend fun getProfile(sourceId: String, editionId: String): BookProfile? = null
    override suspend fun getProfileEntry(sourceId: String, editionId: String): SharedProfileEntry? = null
    override suspend fun putProfile(sourceId: String, editionId: String, profile: BookProfile, provenance: ProfileProvenance) = Unit

    override suspend fun getCover(mergeKey: String): String? = null
    override suspend fun getCovers(mergeKeys: List<String>): Map<String, String> = emptyMap()
    override suspend fun putCover(mergeKey: String, coverUrl: String, provenance: CoverProvenance) = Unit

    /** Published submissions, keyed by the normalized-URL document id (store-level URL dedup). */
    private val submissions = linkedMapOf<String, SubmissionPublication>()

    /** Every publication attempt in call order — the gate tests assert on this. */
    val submissionPuts = mutableListOf<SubmissionPublication>()

    override suspend fun publishSubmission(publication: SubmissionPublication) {
        if (SubmissionPublicationCodec.toMap(publication) == null) return
        submissionPuts += publication
        submissions[SubmissionPublicationCodec.documentId(publication.sourceUrl)] = publication
    }

    override suspend fun getSubmission(sourceUrl: String): SubmissionPublication? =
        submissions[SubmissionPublicationCodec.documentId(sourceUrl)]

    override suspend fun getSubmissionPage(after: SubmissionCursor?, limit: Int): SubmissionPage {
        if (throwOnSubmissionPage) throw IllegalStateException("shared base down")
        val boundedLimit = SubmissionPageLimits.bounded(limit)
        if (boundedLimit == 0) return SubmissionPage(emptyList(), null)
        val ordered = submissions.values.sortedWith(
            compareBy<SubmissionPublication> { it.submittedAt }.thenBy { SubmissionPublicationCodec.documentId(it.sourceUrl) }
        )
        val remaining = ordered.filter { publication ->
            val documentId = SubmissionPublicationCodec.documentId(publication.sourceUrl)
            after == null || publication.submittedAt > after.submittedAt ||
                (publication.submittedAt == after.submittedAt && documentId > after.documentId)
        }
        val page = remaining.take(boundedLimit)
        val nextCursor = page.lastOrNull()
            ?.let { SubmissionCursor(it.submittedAt, SubmissionPublicationCodec.documentId(it.sourceUrl)) }
        return SubmissionPage(page, nextCursor)
    }

    /** Shared tombstones, keyed by the deterministic per-target document id. */
    private val tombstones = linkedMapOf<String, SharedTombstone>()

    val tombstonePuts = mutableListOf<SharedTombstone>()

    override suspend fun putSharedTombstone(tombstone: SharedTombstone) {
        if (SharedTombstoneCodec.toMap(tombstone) == null) return
        tombstonePuts += tombstone
        SharedTombstoneCodec.documentId(tombstone)?.let { tombstones[it] = tombstone }
    }

    override suspend fun getSharedTombstonePage(after: SharedTombstoneCursor?, limit: Int): SharedTombstonePage {
        if (throwOnTombstonePage) throw IllegalStateException("shared base down")
        val boundedLimit = SharedTombstonePageLimits.bounded(limit)
        if (boundedLimit == 0) return SharedTombstonePage(emptyList(), null)
        val ordered = tombstones.values.sortedWith(
            compareBy<SharedTombstone> { it.placedAt }.thenBy { SharedTombstoneCodec.documentId(it) ?: "" }
        )
        val remaining = ordered.filter { tombstone ->
            val documentId = SharedTombstoneCodec.documentId(tombstone) ?: ""
            after == null || tombstone.placedAt > after.placedAt ||
                (tombstone.placedAt == after.placedAt && documentId > after.documentId)
        }
        val page = remaining.take(boundedLimit)
        val nextCursor = page.lastOrNull()
            ?.let { SharedTombstoneCursor(it.placedAt, SharedTombstoneCodec.documentId(it) ?: "") }
        return SharedTombstonePage(page, nextCursor)
    }

    /** Per-device per-day counters: (deviceId, dayKey) -> count. */
    private val submissionCounts = mutableMapOf<Pair<String, String>, Long>()

    override suspend fun getSubmissionCount(deviceId: String, dayKey: String): Long =
        submissionCounts[deviceId to dayKey] ?: 0L

    override suspend fun incrementSubmissionCount(deviceId: String, dayKey: String): Long {
        val next = (submissionCounts[deviceId to dayKey] ?: 0L) + 1
        submissionCounts[deviceId to dayKey] = next
        return next
    }
}
