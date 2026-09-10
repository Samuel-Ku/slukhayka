package com.slukhayka.audiobooks.data.metadata

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Spec-30 T2 (#217) — the Firestore implementation of [SharedBookMetaStore]:
 * reads the shared duration of one Edition from the `book_durations`
 * collection, document per editionId. Thin Android glue (like the transport
 * adapters); pure document shapes are pinned by codec/seam tests, while the
 * write and ordered-page paths are traced against the real Firestore Emulator.
 *
 * Degrade-never-throw: a missing document, a network failure, a timeout or an
 * unreadable document all yield null / an empty map — the caller falls
 * through to the local database silently. The batch read is chunked into
 * Firestore's `whereIn` bound (10 ids per query) so a screen of visible books
 * costs a handful of reads, never one per book; a failing chunk contributes
 * nothing. Firebase itself is optional: [create] returns null when the app
 * has no Firebase configuration (no `google-services.json` keys), so the
 * shared layer simply does not exist and the app behaves exactly as before.
 */
class FirestoreBookMetaStore(private val firestore: FirebaseFirestore) : SharedBookMetaStore {

    override suspend fun getFacet(key: FacetAssertionKey): FacetAssertion? {
        return try {
            val snapshot = firestore.collection(FACET_COLLECTION).document(key.documentId).get()
                .awaitOrNull() ?: return null
            if (!snapshot.exists()) null
            else FacetAssertionCodec.fromMap(snapshot.id, snapshot.data ?: return null)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun putFacet(assertion: FacetAssertion) {
        val document = FacetAssertionCodec.toMap(assertion) ?: return
        runCatching {
            firestore.collection(FACET_COLLECTION).document(assertion.documentId)
                .set(document)
                .awaitOrNull()
        }
    }

    override suspend fun getFacetPage(after: FacetCursor?, limit: Int): FacetPage {
        val boundedLimit = FacetPageLimits.bounded(limit)
        if (boundedLimit == 0) return FacetPage(emptyList(), null)
        return try {
            var query = firestore.collection(FACET_COLLECTION)
                .orderBy(FACET_CURSOR_FIELD)
                .orderBy(FieldPath.documentId())
            if (after != null) query = query.startAfter(after.updatedAt, after.documentId)
            val snapshot = query.limit((boundedLimit + 1).toLong()).get().awaitOrNull()
                ?: return FacetPage(emptyList(), null)
            val pageDocuments = snapshot.documents.take(boundedLimit)
            val assertions = pageDocuments.mapNotNull { document ->
                FacetAssertionCodec.fromMap(document.id, document.data ?: return@mapNotNull null)
            }
            val nextCursor = pageDocuments.lastOrNull()
                ?.let { document ->
                    (document.data?.get(FACET_CURSOR_FIELD) as? Number)?.toLong()
                        ?.let { updatedAt -> FacetCursor(updatedAt, document.id) }
                }
            FacetPage(assertions, nextCursor)
        } catch (e: Exception) {
            FacetPage(emptyList(), null)
        }
    }

    override suspend fun getDuration(editionId: String): Long? {
        return try {
            val snapshot = firestore.collection(COLLECTION).document(editionId).get()
                .awaitOrNull() ?: return null
            if (!snapshot.exists()) null
            else SharedDurationCodec.fromMap(snapshot.data ?: return null)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun getDurations(editionIds: List<String>): Map<String, Long> {
        val ids = editionIds.distinct()
        if (ids.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, Long>()
        for (chunk in ids.chunked(MAX_WHERE_IN)) {
            try {
                val snapshots = firestore.collection(COLLECTION)
                    .whereIn(FieldPath.documentId(), chunk)
                    .get()
                    .awaitOrNull() ?: continue
                for (doc in snapshots.documents) {
                    val decoded = SharedDurationCodec.fromMap(doc.data ?: continue) ?: continue
                    result[doc.id] = decoded
                }
            } catch (e: Exception) {
                // Degrade-never: a failing chunk contributes nothing.
            }
        }
        return result
    }

    override suspend fun putDuration(
        editionId: String,
        durationSeconds: Long,
        provenance: DurationProvenance
    ) {
        if (!DurationContractLimits.isPlausibleEditionId(editionId)) return
        if (!DurationSanity.isPlausible(durationSeconds)) return
        if (!DurationContractLimits.isPlausibleProvenance(provenance)) return

        // One transaction protects the first-write-wins decision against two
        // listeners observing the same gap concurrently. Rules independently
        // deny canonical update/delete, so a client bug cannot bypass this
        // door. Any transport/rules failure remains a silent best-effort miss.
        runCatching {
            firestore.runTransaction { transaction ->
                val canonicalRef = firestore.collection(COLLECTION).document(editionId)
                val canonicalSnapshot = transaction.get(canonicalRef)
                val canonicalDuration = canonicalSnapshot.data
                    ?.let(SharedDurationCodec::fromMap)
                when (
                    DurationObservationPolicy.decide(
                        canonicalDocumentExists = canonicalSnapshot.exists(),
                        canonicalDurationSeconds = canonicalDuration,
                        candidateSeconds = durationSeconds
                    )
                ) {
                    DurationWriteDecision.CreateCanonical -> {
                        transaction.set(
                            canonicalRef,
                            SharedDurationCodec.toMap(durationSeconds, provenance)
                        )
                    }

                    DurationWriteDecision.CreateConflict -> {
                        val conflict = DurationConflict(editionId, durationSeconds, provenance)
                        val conflictRef = firestore.collection(CONFLICT_COLLECTION)
                            .document(DurationConflictId.of(conflict))
                        if (!transaction.get(conflictRef).exists()) {
                            transaction.set(conflictRef, DurationConflictCodec.toMap(conflict))
                        }
                    }

                    DurationWriteDecision.NoOp -> Unit
                }
            }.awaitOrNull()
        }
    }

    override suspend fun getProfile(sourceId: String, editionId: String): BookProfile? {
        return try {
            val snapshot = firestore.collection(PROFILE_COLLECTION).document(profileKey(sourceId, editionId)).get()
                .awaitOrNull() ?: return null
            if (!snapshot.exists()) null
            else BookProfileCodec.fromMap(snapshot.data ?: return null)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun getProfileEntry(sourceId: String, editionId: String): SharedProfileEntry? {
        return try {
            val snapshot = firestore.collection(PROFILE_COLLECTION).document(profileKey(sourceId, editionId)).get()
                .awaitOrNull() ?: return null
            if (!snapshot.exists()) null
            else BookProfileCodec.fromMapEntry(snapshot.data ?: return null)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun putProfile(
        sourceId: String,
        editionId: String,
        profile: BookProfile,
        provenance: ProfileProvenance
    ) {
        // Best-effort fire-and-forget (same contract as the duration write).
        runCatching {
            firestore.collection(PROFILE_COLLECTION).document(profileKey(sourceId, editionId))
                .set(BookProfileCodec.toMap(profile, provenance))
        }
    }

    override suspend fun getCover(mergeKey: String): String? {
        return try {
            val snapshot = firestore.collection(COVER_COLLECTION).document(mergeKey).get()
                .awaitOrNull() ?: return null
            if (!snapshot.exists()) null
            else CoverCodec.fromMap(snapshot.data ?: return null)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun getCovers(mergeKeys: List<String>): Map<String, String> {
        val keys = mergeKeys.distinct().filter { it.isNotBlank() }
        if (keys.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, String>()
        for (chunk in keys.chunked(MAX_WHERE_IN)) {
            try {
                val snapshots = firestore.collection(COVER_COLLECTION)
                    .whereIn(FieldPath.documentId(), chunk)
                    .get()
                    .awaitOrNull() ?: continue
                for (doc in snapshots.documents) {
                    val decoded = CoverCodec.fromMap(doc.data ?: continue) ?: continue
                    result[doc.id] = decoded
                }
            } catch (e: Exception) {
                // Degrade-never: a failing chunk contributes nothing.
            }
        }
        return result
    }

    override suspend fun putCover(
        mergeKey: String,
        coverUrl: String,
        provenance: CoverProvenance
    ) {
        // Best-effort fire-and-forget (same contract as the duration write);
        // set() on the mergeKey document key is idempotent — a re-seed
        // replaces, never duplicates.
        runCatching {
            firestore.collection(COVER_COLLECTION).document(mergeKey)
                .set(CoverCodec.toMap(coverUrl, provenance))
        }
    }

    override suspend fun publishSubmission(publication: SubmissionPublication) {
        val document = SubmissionPublicationCodec.toMap(publication) ?: return
        // Best-effort fire-and-forget; the document key is the normalized
        // URL's hash — the same link re-published REPLACE-no-ops (URL dedup).
        runCatching {
            firestore.collection(SUBMISSION_COLLECTION)
                .document(SubmissionPublicationCodec.documentId(publication.sourceUrl))
                .set(document)
        }
    }

    override suspend fun getSubmissionPage(after: SubmissionCursor?, limit: Int): SubmissionPage {
        val boundedLimit = SubmissionPageLimits.bounded(limit)
        if (boundedLimit == 0) return SubmissionPage(emptyList(), null)
        return try {
            var query = firestore.collection(SUBMISSION_COLLECTION)
                .orderBy(SUBMISSION_CURSOR_FIELD)
                .orderBy(FieldPath.documentId())
            if (after != null) query = query.startAfter(after.submittedAt, after.documentId)
            val snapshot = query.limit((boundedLimit + 1).toLong()).get().awaitOrNull()
                ?: return SubmissionPage(emptyList(), null)
            val pageDocuments = snapshot.documents.take(boundedLimit)
            val publications = pageDocuments.mapNotNull { document ->
                SubmissionPublicationCodec.fromMap(document.data ?: return@mapNotNull null)
            }
            val nextCursor = pageDocuments.lastOrNull()
                ?.let { document ->
                    (document.data?.get(SUBMISSION_CURSOR_FIELD) as? Number)?.toLong()
                        ?.let { submittedAt -> SubmissionCursor(submittedAt, document.id) }
                }
            SubmissionPage(publications, nextCursor)
        } catch (e: Exception) {
            SubmissionPage(emptyList(), null)
        }
    }

    override suspend fun getSubmission(sourceUrl: String): SubmissionPublication? {
        return try {
            val snapshot = firestore.collection(SUBMISSION_COLLECTION)
                .document(SubmissionPublicationCodec.documentId(sourceUrl)).get()
                .awaitOrNull() ?: return null
            if (!snapshot.exists()) null
            else SubmissionPublicationCodec.fromMap(snapshot.data ?: return null)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun putSharedTombstone(tombstone: SharedTombstone) {
        val document = SharedTombstoneCodec.toMap(tombstone) ?: return
        val documentId = SharedTombstoneCodec.documentId(tombstone) ?: return
        // Best-effort fire-and-forget; the key is deterministic per TARGET,
        // so re-placing the same tombstone REPLACE-no-ops.
        runCatching {
            firestore.collection(TOMBSTONE_COLLECTION).document(documentId).set(document)
        }
    }

    override suspend fun getSharedTombstonePage(after: SharedTombstoneCursor?, limit: Int): SharedTombstonePage {
        val boundedLimit = SharedTombstonePageLimits.bounded(limit)
        if (boundedLimit == 0) return SharedTombstonePage(emptyList(), null)
        return try {
            var query = firestore.collection(TOMBSTONE_COLLECTION)
                .orderBy(TOMBSTONE_CURSOR_FIELD)
                .orderBy(FieldPath.documentId())
            if (after != null) query = query.startAfter(after.placedAt, after.documentId)
            val snapshot = query.limit((boundedLimit + 1).toLong()).get().awaitOrNull()
                ?: return SharedTombstonePage(emptyList(), null)
            val pageDocuments = snapshot.documents.take(boundedLimit)
            val tombstones = pageDocuments.mapNotNull { document ->
                SharedTombstoneCodec.fromMap(document.data ?: return@mapNotNull null)
            }
            val nextCursor = pageDocuments.lastOrNull()
                ?.let { document ->
                    (document.data?.get(TOMBSTONE_CURSOR_FIELD) as? Number)?.toLong()
                        ?.let { placedAt -> SharedTombstoneCursor(placedAt, document.id) }
                }
            SharedTombstonePage(tombstones, nextCursor)
        } catch (e: Exception) {
            SharedTombstonePage(emptyList(), null)
        }
    }

    override suspend fun getSubmissionCount(deviceId: String, dayKey: String): Long {
        return try {
            val snapshot = firestore.collection(COUNTER_COLLECTION).document("$deviceId|$dayKey").get()
                .awaitOrNull() ?: return 0L
            if (!snapshot.exists()) 0L
            else (snapshot.data?.get(COUNTER_FIELD) as? Number)?.toLong() ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    override suspend fun incrementSubmissionCount(deviceId: String, dayKey: String): Long {
        // Firestore FieldValue.increment is atomic across devices — the
        // shared counter never races. The returned count is best-effort.
        return try {
            val ref = firestore.collection(COUNTER_COLLECTION).document("$deviceId|$dayKey")
            ref.set(mapOf(COUNTER_FIELD to FieldValue.increment(1)), com.google.firebase.firestore.SetOptions.merge())
                .awaitOrNull()
            (ref.get().awaitOrNull()?.data?.get(COUNTER_FIELD) as? Number)?.toLong() ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    override suspend fun getRefusalCount(sourceId: String): Long {
        if (sourceId.isBlank()) return 0L
        return try {
            val snapshot = firestore.collection(REFUSAL_COLLECTION).document(sourceId).get()
                .awaitOrNull() ?: return 0L
            if (!snapshot.exists()) 0L
            else SourceRefusalCountCodec.fromMap(snapshot.data ?: return 0L) ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    override suspend fun publishRefusalVote(sourceId: String, uid: String): Boolean {
        val vote = SourceRefusalVoteCodec.toMap(sourceId, uid) ?: return false
        val voteId = SourceRefusalVoteCodec.documentId(sourceId, uid)
        return try {
            firestore.runTransaction { transaction ->
                val voteRef = firestore.collection(REFUSAL_VOTE_COLLECTION).document(voteId)
                if (transaction.get(voteRef).exists()) return@runTransaction true
                transaction.set(voteRef, vote)
                val aggregateRef = firestore.collection(REFUSAL_COLLECTION).document(sourceId)
                val aggregateSnapshot = transaction.get(aggregateRef)
                val next = (aggregateSnapshot.data?.let(SourceRefusalCountCodec::fromMap) ?: 0L) + 1
                transaction.set(aggregateRef, SourceRefusalCountCodec.toMap(next))
                true
            }.awaitOrNull() ?: false
        } catch (e: Exception) {
            false
        }
    }

    /** The deterministic document key of one Source×Edition profile. */
    private fun profileKey(sourceId: String, editionId: String): String = "$sourceId|$editionId"

    /** Bridges the Play Services [Task] onto a coroutine: result or null. */
    private suspend fun <T> Task<T>.awaitOrNull(): T? = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resume(null) }
    }

    companion object {
        private const val COLLECTION = "book_durations"

        /** Spec-42 #311 — compact Work/Edition assertions ordered by `updatedAt`. */
        private const val FACET_COLLECTION = "book_facets"
        private const val FACET_CURSOR_FIELD = "updatedAt"

        /** Spec-42 #303 — idempotent Edition/value/method conflict evidence. */
        private const val CONFLICT_COLLECTION = "book_duration_conflicts"

        /** Spec-32 T1 — the shared profile collection, keyed sourceId|editionId. */
        private const val PROFILE_COLLECTION = "book_profiles"

        /**
         * Spec-30 T3 (#218) — the shared canonical-cover collection, keyed by
         * the Work mergeKey (one cover per Work, shared across narrations).
         */
        private const val COVER_COLLECTION = "book_covers"

        /**
         * ADR-0035 / #605 — the shared listener-submission collection, keyed
         * by the normalized URL's hash; ordered by `submittedAt` for the
         * consuming delta lane.
         */
        private const val SUBMISSION_COLLECTION = "book_submissions"
        private const val SUBMISSION_CURSOR_FIELD = "submittedAt"

        /**
         * ADR-0035 / #607 — the shared curator-tombstone collection, keyed
         * deterministically per target; ordered by `placedAt` for the
         * consuming delta lane.
         */
        private const val TOMBSTONE_COLLECTION = "book_tombstones"
        private const val TOMBSTONE_CURSOR_FIELD = "placedAt"

        /**
         * ADR-0035 / #607 — the per-device daily submission counters,
         * document per `deviceId|dayKey` with an atomic `count` field.
         */
        private const val COUNTER_COLLECTION = "submission_daily_counters"
        private const val COUNTER_FIELD = "count"
        /**
         * Spec-49 T5 — the anonymous per-source refusal counters (one
         * document per sourceId) and the per-device vote documents
         * (`{sourceId}_{uid}`) behind the one-device-once gate.
         */
        private const val REFUSAL_COLLECTION = "source_refusals"
        private const val REFUSAL_VOTE_COLLECTION = "source_refusal_votes"

        /** Firestore's `whereIn` value bound — the batch chunk size. */
        private const val MAX_WHERE_IN = 10

        /**
         * The default Firebase app's Firestore, or null when Firebase is not
         * configured (no google-services.json — [FirebaseApp.initializeApp]
         * then returns null instead of throwing).
         */
        fun create(context: Context): FirestoreBookMetaStore? {
            val app = FirebaseApp.getApps(context).firstOrNull()
                ?: FirebaseApp.initializeApp(context)
                ?: return null
            return FirestoreBookMetaStore(FirebaseFirestore.getInstance(app))
        }
    }
}
