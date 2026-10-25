package com.slukhayka.audiobooks.data.collective

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * #1238 — the thin Firestore transport of the shared lease.
 *
 * Two documents back one block identity: the public block row in
 * `catalog_blocks` and its lease row in `catalog_block_leases` (not public:
 * it carries an owner token, so its rules require an authenticated install).
 * [acquire] and [publish] are single transactions, which is what makes the
 * fence and the publication atomic: a publish either sees the exact owner,
 * fence and unexpired deadline it was granted, or writes nothing.
 *
 * The transaction callbacks below are PURE — they only read and write
 * documents through the [Transaction] the server gave them. No source request,
 * no Room write and no UI callback runs inside a callback the server may retry;
 * the block is already encoded and all decisions are plain data.
 *
 * Expiry is compared against the device clock, which may be skewed. The fence
 * is what keeps that honest: a wrong clock can make a lease expire early (a
 * needless extra source request) but the stale owner still cannot publish over
 * the new one.
 *
 * Degrade-never-throw: absence, a network failure or a rules rejection answers
 * null (or a no-op), so the caller keeps its local state. Firebase stays
 * optional: [create] returns null without configuration.
 */
class FirestoreCollectiveSharedLeaseStore(
    private val firestore: FirebaseFirestore
) : CollectiveSharedLeaseStore {

    override suspend fun acquire(
        blockKey: String,
        ownerToken: String,
        now: Long,
        ttlMs: Long
    ): CollectiveLeaseAttempt {
        val leaseRef = leaseDocument(blockKey) ?: return CollectiveLeaseAttempt.Unavailable
        return try {
            firestore.runTransaction { transaction ->
                val current = transaction.get(leaseRef)
                val currentOwner = current.getString(FIELD_OWNER_TOKEN).orEmpty()
                val currentFence = current.getLong(FIELD_FENCE) ?: 0L
                val currentExpiresAt = current.getLong(FIELD_EXPIRES_AT) ?: 0L
                val sameOwner = currentOwner == ownerToken
                val liveOtherOwner = current.exists() && !sameOwner && now < currentExpiresAt
                if (liveOtherOwner) {
                    CollectiveLeaseAttempt.Busy
                } else {
                    val fence = when {
                        !current.exists() -> 1L
                        sameOwner && now < currentExpiresAt -> currentFence
                        else -> currentFence + 1L
                    }
                    val expiresAt = now + ttlMs
                    transaction.set(
                        leaseRef,
                        mapOf(
                            FIELD_BLOCK_KEY to blockKey,
                            FIELD_OWNER_TOKEN to ownerToken,
                            FIELD_FENCE to fence,
                            FIELD_EXPIRES_AT to expiresAt
                        )
                    )
                    CollectiveLeaseAttempt.Granted(
                        CollectiveLeaseGrant(blockKey, ownerToken, fence, expiresAt)
                    )
                }
            }.awaitOrNull() ?: CollectiveLeaseAttempt.Unavailable
        } catch (e: Exception) {
            CollectiveLeaseAttempt.Unavailable
        }
    }

    override suspend fun publish(
        block: CollectiveFeedBlock,
        grant: CollectiveLeaseGrant,
        now: Long
    ): CollectiveFeedBlock? {
        val leaseRef = leaseDocument(block.blockKey) ?: return null
        val blockRef = FirestoreCollectiveBlockStore.blockDocument(firestore, block.blockKey) ?: return null
        return try {
            firestore.runTransaction { transaction ->
                val lease = transaction.get(leaseRef)
                val stillMine = lease.getString(FIELD_OWNER_TOKEN) == grant.ownerToken &&
                    (lease.getLong(FIELD_FENCE) ?: 0L) == grant.fence &&
                    now < (lease.getLong(FIELD_EXPIRES_AT) ?: 0L)
                if (!stillMine) {
                    null
                } else {
                    val currentRevision = transaction.get(blockRef).getLong(FIELD_REVISION) ?: 0L
                    val published = block.copy(revision = currentRevision + 1L)
                    val document = CollectiveBlockCodec.toMap(published)
                    if (document == null) null else {
                        transaction.set(blockRef, document)
                        published
                    }
                }
            }.awaitOrNull()
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun release(grant: CollectiveLeaseGrant, now: Long) {
        val leaseRef = leaseDocument(grant.blockKey) ?: return
        runCatching {
            firestore.runTransaction { transaction ->
                val lease = transaction.get(leaseRef)
                val stillMine = lease.getString(FIELD_OWNER_TOKEN) == grant.ownerToken &&
                    (lease.getLong(FIELD_FENCE) ?: 0L) == grant.fence
                if (stillMine) {
                    transaction.set(
                        leaseRef,
                        mapOf(
                            FIELD_BLOCK_KEY to grant.blockKey,
                            FIELD_OWNER_TOKEN to "",
                            FIELD_FENCE to grant.fence,
                            FIELD_EXPIRES_AT to 0L
                        )
                    )
                }
                Unit
            }.awaitOrNull()
        }
    }

    override suspend fun published(blockKey: String): CollectiveFeedBlock? {
        val ref = FirestoreCollectiveBlockStore.blockDocument(firestore, blockKey) ?: return null
        return try {
            val snapshot = ref.get().awaitOrNull() ?: return null
            snapshot.data?.let { CollectiveBlockCodec.fromMap(it) }
        } catch (e: Exception) {
            null
        }
    }

    private fun leaseDocument(blockKey: String): DocumentReference? {
        val ref = parseCollectiveBlockKey(blockKey) ?: return null
        return firestore.collection(LEASE_COLLECTION)
            .document(collectiveBlockDocumentId(ref))
    }

    /** Bridges the Play Services [Task] onto a coroutine: result or null. */
    private suspend fun <T> Task<T>.awaitOrNull(): T? = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resume(null) }
    }

    companion object {
        internal const val LEASE_COLLECTION = "catalog_block_leases"
        internal const val FIELD_BLOCK_KEY = "blockKey"
        internal const val FIELD_OWNER_TOKEN = "ownerToken"
        internal const val FIELD_FENCE = "fence"
        internal const val FIELD_EXPIRES_AT = "expiresAt"
        internal const val FIELD_REVISION = "revision"

        /** Null without Firebase configuration — the lane then does not exist. */
        fun create(context: Context): FirestoreCollectiveSharedLeaseStore? {
            val app = FirebaseApp.getApps(context).firstOrNull()
                ?: FirebaseApp.initializeApp(context)
                ?: return null
            return FirestoreCollectiveSharedLeaseStore(FirebaseFirestore.getInstance(app))
        }
    }
}
