package com.slukhayka.audiobooks.data.metadata

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FieldPath
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
                ?.takeIf { snapshot.size() > boundedLimit }
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
