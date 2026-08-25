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

/**
 * A recording in-memory [SharedBookMetaStore] for tests: duration puts are
 * recorded (and can be made to throw, to prove best-effort write-back), the
 * profile/cover surfaces are inert stubs.
 */
class FakeSharedBookMetaStore(
    var throwOnPut: Boolean = false
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
            ?.takeIf { remaining.size > assertions.size }
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
}
