package com.slukhayka.audiobooks.data.reviews

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Identity of the confirmed public Work cache; private pending overlays are never a namespace. */
data class ReviewSnapshotNamespace(
    val projectId: String,
    val firebaseAppName: String,
    val applicationId: String,
    val databaseId: String = "(default)"
)

sealed interface ReviewSnapshotBytes {
    data object Missing : ReviewSnapshotBytes
    data class Data(val bytes: ByteArray) : ReviewSnapshotBytes
    data object Failure : ReviewSnapshotBytes
}

/** Bytes boundary, distinct from the SDK's own persistent mutation queue. */
interface ReviewSnapshotStorage {
    val scopeId: String
    fun read(key: String): ReviewSnapshotBytes
    fun write(key: String, bytes: ByteArray): Boolean
    fun keys(): List<String>?
}

enum class ReviewSnapshotOrigin {
    DEFAULT_OR_CACHE,
    /** A new SERVER query must have started after a successful SDK queue-drain await. */
    POST_DRAIN_SERVER
}

data class ReviewSnapshotDocument(
    val documentId: String,
    val review: ListenerReview?,
    val hasPendingWrites: Boolean
)

data class ReviewSnapshotFrame(
    val documents: List<ReviewSnapshotDocument>,
    val fromCache: Boolean,
    val queryHasPendingWrites: Boolean,
    val origin: ReviewSnapshotOrigin = ReviewSnapshotOrigin.DEFAULT_OR_CACHE
)

interface ReviewSnapshotDrainToken

interface ReviewSnapshotReadToken
interface ReviewSnapshotMutationToken

interface ReviewSaveAcknowledgement {
    val receipt: ReviewWriteReceipt.Queued
    fun backendSettled(published: Boolean)
}

interface ReviewDeleteAcknowledgement {
    val receipt: ReviewDeleteReceipt.Queued
    fun backendSettled(deleted: Boolean)
}

/**
 * Keeps confirmed public rows on disk independently of Firebase's pending overlay.
 * The shared coordinator contains read/mutation ordering, never review payloads.
 * The Firestore adapter supplies raw SDK metadata and independent actual Task signals.
 */
class ConfirmedReviewSnapshots(
    private val namespace: ReviewSnapshotNamespace,
    private val storage: ReviewSnapshotStorage,
    private val acknowledgementScope: CoroutineScope = sharedReviewAcknowledgementScope
) {
    init {
        require(namespace.projectId.isNotBlank())
        require(namespace.firebaseAppName.isNotBlank())
        require(namespace.applicationId.isNotBlank())
        require(namespace.databaseId == "(default)")
        require(storage.scopeId.isNotBlank())
    }

    private val coordinator = coordinators.computeIfAbsent(CoordinatorKey(namespace, storage.scopeId)) {
        ReadCoordinator()
    }

    private data class CoordinatorKey(val namespace: ReviewSnapshotNamespace, val scopeId: String)
    private class ReadCoordinator {
        var nextRead: Long = 0
        var revision: Long = 0
        var nextMutation: Long = 0
        var mutationVersion: Long = 0
        val latestRead = mutableMapOf<String, Long>()
        val latestAccepted = mutableMapOf<String, Long>()
        val ackCacheHealth = mutableMapOf<String, AckCacheHealth>()
        // Failed-key and incomplete-coverage metadata only, never a queued deletion payload.
        val deleteCacheHealth = mutableMapOf<String, DeleteCacheHealth>()
        /** Durable accepted recreation proof is metadata only, never a review or queued intent. */
        val durableRecreations = mutableSetOf<String>()
    }
    private class AckCacheHealth {
        val failedDocumentIds = mutableSetOf<String>()
        var requiresCompleteRepair = false
    }
    private data class ReadToken(
        val coordinator: ReadCoordinator,
        val workId: String,
        val sequence: Long,
        val revision: Long,
        val mutationVersion: Long,
        val serverEligible: Boolean
    ) : ReviewSnapshotReadToken
    private data class DrainToken(val coordinator: ReadCoordinator, val mutationVersion: Long) : ReviewSnapshotDrainToken

    private data class MutationToken(
        val coordinator: ReadCoordinator,
        val documentId: String,
        val sequence: Long
    ) : ReviewSnapshotMutationToken

    fun beginRead(workId: String): ReviewSnapshotReadToken = synchronized(coordinator) {
        beginReadLocked(workId, serverEligible = false)
    }

    private fun beginReadLocked(workId: String, serverEligible: Boolean): ReviewSnapshotReadToken {
        require(workId.isNotBlank() && workId.length <= ListenerReviewLimits.MAX_WORK_ID_LEN)
        val sequence = ++coordinator.nextRead
        coordinator.latestRead[workId] = sequence
        return ReadToken(coordinator, workId, sequence, coordinator.revision, coordinator.mutationVersion, serverEligible)
    }

    fun beginDrain(): ReviewSnapshotDrainToken = synchronized(coordinator) {
        DrainToken(coordinator, coordinator.mutationVersion)
    }

    /** Adapter calls this only AFTER the real SDK drain succeeds, BEFORE initiating a new SERVER query. */
    fun beginServerRead(workId: String, drain: ReviewSnapshotDrainToken): ReviewSnapshotReadToken? = synchronized(coordinator) {
        val captured = drain as? DrainToken ?: return@synchronized null
        if (captured.coordinator !== coordinator || captured.mutationVersion != coordinator.mutationVersion) return@synchronized null
        beginReadLocked(workId, serverEligible = true)
    }

    /** Only short synchronous SDK registration/submission/listener attachment; never backend/cache I/O. */
    fun <T> enqueueMutation(documentId: String, submit: (ReviewSnapshotMutationToken) -> T): T = synchronized(coordinator) {
        submit(beginMutation(documentId))
    }

    fun beginMutation(documentId: String): ReviewSnapshotMutationToken = synchronized(coordinator) {
        require(documentId.isNotBlank() && documentId.toByteArray(Charsets.UTF_8).size <= MAX_STRING_BYTES)
        ++coordinator.revision
        ++coordinator.mutationVersion
        MutationToken(coordinator, documentId, ++coordinator.nextMutation)
    }

    /** The independent producer finishes a confirmed-cache attempt before exposing backend success. */
    fun saveAcknowledgement(token: ReviewSnapshotMutationToken, review: ListenerReview): ReviewSaveAcknowledgement {
        val mutation = token as? MutationToken
        require(mutation != null && mutation.coordinator === coordinator)
        require(validReview(review, review.workId))
        require(mutation.documentId == ListenerReviewCodec.documentId(review.workId, review.uid))
        return object : ReviewSaveAcknowledgement {
            private val settled = AtomicBoolean(false)
            private val remote = CompletableDeferred<ReviewRemoteResult>()
            override val receipt = ReviewWriteReceipt.Queued { remote.await() }

            override fun backendSettled(published: Boolean) {
                if (!settled.compareAndSet(false, true)) return
                acknowledgementScope.launch {
                    if (published) synchronized(coordinator) {
                        if (mutation.sequence > (coordinator.latestAccepted[mutation.documentId] ?: 0L)) {
                            coordinator.latestAccepted[mutation.documentId] = mutation.sequence
                            ++coordinator.revision
                            // Advance accepted order even if disk fails; old callbacks cannot restore stale truth.
                            try {
                                cacheAcceptedReview(review, mutation.documentId)
                            } catch (_: Exception) {
                                recordAckCacheFailure(review.workId, mutation.documentId, requiresCompleteRepair = true)
                            }
                        }
                    }
                    remote.complete(if (published) ReviewRemoteResult.PUBLISHED else ReviewRemoteResult.FAILED)
                }
            }
        }
    }

    /** SDK read unavailable: confirmed file truth only, never invented pending/deleting identities. */
    suspend fun lastGood(workId: String): ReviewReadResult = withContext(Dispatchers.IO) {
        synchronized(coordinator) {
            require(workId.isNotBlank() && workId.length <= ListenerReviewLimits.MAX_WORK_ID_LEN)
            when (val stored = load(workKey(workId), workId)) {
                is Loaded.Data -> ReviewReadResult.Snapshot(
                    stored.reviews.sortedByDescending { it.createdAt }, emptyList(), fromCache = true, readFailed = true
                )
                else -> ReviewReadResult.Failure
            }
        }
    }

    fun deleteAcknowledgement(token: ReviewSnapshotMutationToken): ReviewDeleteAcknowledgement {
        val mutation = token as? MutationToken
        require(mutation != null && mutation.coordinator === coordinator)
        return object : ReviewDeleteAcknowledgement {
            private val settled = AtomicBoolean(false)
            private val remote = CompletableDeferred<Boolean>()
            override val receipt = ReviewDeleteReceipt.Queued { remote.await() }
            override fun backendSettled(deleted: Boolean) {
                if (!settled.compareAndSet(false, true)) return
                acknowledgementScope.launch {
                    if (deleted) synchronized(coordinator) {
                        if (mutation.sequence > (coordinator.latestAccepted[mutation.documentId] ?: 0L)) {
                            coordinator.latestAccepted[mutation.documentId] = mutation.sequence
                            ++coordinator.revision
                            try {
                                coordinator.durableRecreations.remove(mutation.documentId)
                                cacheAcceptedDelete(mutation.documentId)
                            } catch (_: Exception) {
                                recordDeleteCacheFailure(mutation.documentId, incompleteCoverage = true)
                            }
                        }
                    }
                    remote.complete(deleted)
                }
            }
        }
    }

    /** Health/scan retain identity facts only, never all Work review payloads. */
    private data class DeleteCacheHealth(val failedKeys: MutableSet<String> = mutableSetOf(), var incompleteCoverage: Boolean = false)
    private data class KnownWorkFile(val key: String, val workId: String, val byteCount: Long, val matched: Boolean)
    private data class NamespaceScan(
        val files: List<KnownWorkFile>, val failedKeys: Set<String>, val incompleteCoverage: Boolean = false,
        val validationBytes: Long = 0L, val matchedBytes: Long = 0L, val presentIds: Set<String> = emptySet()
    )

    private fun recordDeleteCacheFailure(documentId: String, failedKeys: Set<String> = emptySet(), incompleteCoverage: Boolean = false) {
        val health = coordinator.deleteCacheHealth.getOrPut(documentId) { DeleteCacheHealth() }
        // An incomplete subsequent scan never discards precise failures already observed.
        health.failedKeys.addAll(failedKeys)
        health.incompleteCoverage = health.incompleteCoverage || incompleteCoverage
    }

    private fun scanKnownWorks(documentIds: Set<String>, reserveDeletion: Boolean = false): NamespaceScan {
        val enumerated = try { storage.keys() } catch (_: Exception) { null }
            ?: return NamespaceScan(emptyList(), emptySet(), incompleteCoverage = true)
        // keys()/listFiles already materialize names; this bounds subsequent processing, not directory discovery.
        val keys = enumerated.distinct()
        if (keys.size > MAX_NAMESPACE_WORKS) return NamespaceScan(emptyList(), emptySet(), incompleteCoverage = true)
        val files = mutableListOf<KnownWorkFile>()
        val failed = mutableSetOf<String>()
        val present = mutableSetOf<String>()
        var validationBytes = 0L
        var matchedBytes = 0L
        for (key in keys) {
            try {
                require(key.matches(Regex("[a-f0-9]{64}")))
                val payload = try { storage.read(key) } catch (_: Exception) { ReviewSnapshotBytes.Failure }
                if (payload !is ReviewSnapshotBytes.Data || payload.bytes.size > MAX_BYTES) {
                    // Failure/Missing may already have consumed a whole bounded Atomic read; stop uncharged uncertainty.
                    failed.add(key)
                    return NamespaceScan(emptyList(), failed, incompleteCoverage = true)
                }
                val bytes = payload
                validationBytes += bytes.bytes.size
                // Existing bounded read may discover overflow by one extra <=4MiB file; do not decode it.
                if (validationBytes > MAX_NAMESPACE_OPERATION_BYTES) return NamespaceScan(emptyList(), failed, incompleteCoverage = true)
                val workId = DataInputStream(ByteArrayInputStream(bytes.bytes)).use { input ->
                    require(input.readInt() == MAGIC && input.readInt() == VERSION)
                    require(input.readNamespace() == namespace)
                    input.readText().also { require(it.isNotBlank() && it.length <= ListenerReviewLimits.MAX_WORK_ID_LEN) }
                }
                require(workKey(workId) == key)
                // One complete codec validation, then immediately discard this file's decoded DTO list.
                var matched = false
                for (review in decode(bytes.bytes, workId)) {
                    val id = ListenerReviewCodec.documentId(review.workId, review.uid)
                    if (id in documentIds) { matched = true; present.add(id) }
                }
                if (matched) matchedBytes += bytes.bytes.size
                files += KnownWorkFile(key, workId, bytes.bytes.size.toLong(), matched)
            } catch (_: Exception) { failed.add(key) }
        }
        if (reserveDeletion && validationBytes + 3L * matchedBytes > MAX_NAMESPACE_OPERATION_BYTES) {
            return NamespaceScan(emptyList(), failed, incompleteCoverage = true)
        }
        return NamespaceScan(files, failed, validationBytes = validationBytes, matchedBytes = matchedBytes, presentIds = present)
    }

    private fun cacheAcceptedDelete(documentId: String) {
        val scan = scanKnownWorks(setOf(documentId), reserveDeletion = true)
        if (scan.incompleteCoverage || scan.failedKeys.isNotEmpty()) {
            recordDeleteCacheFailure(documentId, scan.failedKeys, scan.incompleteCoverage)
            return // Validate/reserve the WHOLE operation before any deletion write.
        }
        val matches = scan.files.filter { it.matched }
        if (matches.isEmpty()) {
            recordDeleteCacheFailure(documentId, emptySet())
            return // Backend success is real; unknown Work identity is not a cache-success claim.
        }
        val failedKeys = mutableSetOf<String>()
        for (file in matches) {
            // Reread/revalidate one matching Work, under the same namespace coordinator.
            val current = try {
                val bytes = storage.read(file.key) as? ReviewSnapshotBytes.Data ?: error("Confirmed file unavailable")
                // Preserve the operation reservation if an external filesystem change occurs between passes.
                require(bytes.bytes.size.toLong() <= file.byteCount)
                Loaded.Data(decode(bytes.bytes, file.workId))
            } catch (_: Exception) { null }
            if (current == null) {
                failedKeys.add(file.key)
                recordAckCacheFailure(file.workId, documentId, requiresCompleteRepair = true)
                recordDeleteCacheFailure(documentId, failedKeys, incompleteCoverage = true)
                return // Unknown second-pass consumption also stops this attempt immediately.
            }
            val remaining = current.reviews.filterNot { ListenerReviewCodec.documentId(it.workId, it.uid) == documentId }
            if (persist(file.key, file.workId, remaining)) repairAckCacheRows(file.workId, listOf(documentId))
            else {
                failedKeys.add(file.key)
                recordAckCacheFailure(file.workId, documentId, requiresCompleteRepair = false)
                recordDeleteCacheFailure(documentId, failedKeys, incompleteCoverage = true)
                return // A failed committed-readback cannot justify continuing an uncertain I/O budget.
            }
        }
        if (failedKeys.isNotEmpty()) recordDeleteCacheFailure(documentId, failedKeys)
        else {
            val history = coordinator.deleteCacheHealth[documentId]
            val validatedKeys = scan.files.mapTo(mutableSetOf()) { it.key }
            // A successful repeated deletion does not make a vanished historically failed key repaired.
            if (history == null || validatedKeys.containsAll(history.failedKeys)) {
                coordinator.deleteCacheHealth.remove(documentId)
                coordinator.durableRecreations.remove(documentId)
            }
        }
    }

    /** Eligible durable truth write plus complete bounded validation, never a queued retry or partial scan. */
    private fun repairDeleteHealthAfterTruthWrite(onlyRecreatedId: String? = null) {
        if (coordinator.deleteCacheHealth.isEmpty()) return
        val scan = scanKnownWorks(coordinator.deleteCacheHealth.keys.toSet())
        if (scan.incompleteCoverage || scan.failedKeys.isNotEmpty()) {
            for (documentId in coordinator.deleteCacheHealth.keys.toList()) {
                if (onlyRecreatedId == null || onlyRecreatedId == documentId) {
                    recordDeleteCacheFailure(documentId, scan.failedKeys, scan.incompleteCoverage)
                }
            }
            return
        }
        val validKeys = scan.files.mapTo(mutableSetOf()) { it.key }
        for ((documentId, health) in coordinator.deleteCacheHealth.toMap()) {
            if (onlyRecreatedId != null && documentId != onlyRecreatedId) continue
            if (!validKeys.containsAll(health.failedKeys)) continue
            if (documentId !in scan.presentIds || documentId in coordinator.durableRecreations) {
                coordinator.deleteCacheHealth.remove(documentId)
                coordinator.durableRecreations.remove(documentId)
            }
        }
    }

    private fun cacheAcceptedReview(review: ListenerReview, documentId: String) {
        val key = workKey(review.workId)
        val stored = load(key, review.workId)
        val unknownCollection = stored is Loaded.Failure ||
            coordinator.ackCacheHealth[review.workId]?.requiresCompleteRepair == true
        if (unknownCollection) {
            // A single accepted row cannot reconstruct other public rows from unavailable/corrupt bytes.
            recordAckCacheFailure(review.workId, documentId, requiresCompleteRepair = true)
            return
        }
        val rows = (stored as? Loaded.Data)?.reviews.orEmpty()
            .associateBy { ListenerReviewCodec.documentId(it.workId, it.uid) }.toMutableMap()
        rows[documentId] = review
        if (persist(key, review.workId, rows.values.sortedByDescending { it.createdAt })) {
            repairAckCacheRows(review.workId, listOf(documentId))
            if (documentId in coordinator.deleteCacheHealth) {
                coordinator.durableRecreations.add(documentId)
                // Own failed deletion can be superseded only by durable actual accepted recreation.
                // Validate all previously failed keys; unrelated broken keys remain honestly degraded.
                repairDeleteHealthAfterTruthWrite(onlyRecreatedId = documentId)
            }
        } else {
            recordAckCacheFailure(review.workId, documentId, requiresCompleteRepair = false)
        }
    }

    private fun recordAckCacheFailure(workId: String, documentId: String, requiresCompleteRepair: Boolean) {
        val health = coordinator.ackCacheHealth.getOrPut(workId) { AckCacheHealth() }
        health.failedDocumentIds.add(documentId)
        health.requiresCompleteRepair = health.requiresCompleteRepair || requiresCompleteRepair
    }

    private fun repairAckCacheRows(workId: String, documentIds: Collection<String>) {
        val health = coordinator.ackCacheHealth[workId] ?: return
        health.failedDocumentIds.removeAll(documentIds.toSet())
        if (!health.requiresCompleteRepair && health.failedDocumentIds.isEmpty()) {
            coordinator.ackCacheHealth.remove(workId)
        }
    }

    suspend fun project(
        token: ReviewSnapshotReadToken,
        uid: String,
        frame: ReviewSnapshotFrame
    ): ReviewReadResult = withContext(Dispatchers.IO) {
        synchronized(coordinator) {
            val read = token as? ReadToken ?: return@synchronized ReviewReadResult.Failure
            if (read.coordinator !== coordinator || coordinator.latestRead[read.workId] != read.sequence ||
                coordinator.revision != read.revision || coordinator.mutationVersion != read.mutationVersion) {
                return@synchronized ReviewReadResult.Failure
            }
            val duplicateIds = frame.documents.groupingBy { it.documentId }.eachCount()
                .filterValues { it > 1 }.keys
            val validDocuments = frame.documents.filter { document ->
                document.documentId !in duplicateIds && document.review?.let {
                    validReview(it, read.workId) && document.documentId == ListenerReviewCodec.documentId(it.workId, it.uid)
                } == true
            }
            val invalidFrame = validDocuments.size != frame.documents.size
            val serverOrigin = frame.origin == ReviewSnapshotOrigin.POST_DRAIN_SERVER
            if (serverOrigin != read.serverEligible) return@synchronized ReviewReadResult.Failure
            val authoritative = read.serverEligible
            if (authoritative && (frame.fromCache || frame.queryHasPendingWrites ||
                    frame.documents.any { it.hasPendingWrites } || invalidFrame)) {
                return@synchronized ReviewReadResult.Failure
            }

            // A new object always loads the actual file. No confirmed rows live in the coordinator.
            val key = workKey(read.workId)
            val stored = load(key, read.workId)
            val baseline = (stored as? Loaded.Data)?.reviews.orEmpty()
            val observed = validDocuments.filterNot { it.hasPendingWrites }.map { it.review!! }
            val confirmed = if (authoritative) {
                observed
            } else {
                // Missing/pending documents never erase last-good rows; valid SDK truth wins per id.
                val rows = baseline.associateBy { ListenerReviewCodec.documentId(it.workId, it.uid) }.toMutableMap()
                observed.forEach { rows[ListenerReviewCodec.documentId(it.workId, it.uid)] = it }
                rows.values.toList()
            }.sortedByDescending { it.createdAt }
            val pending = validDocuments.filter { it.hasPendingWrites }.map { it.review!! }
                .filter { it.uid == uid }.sortedByDescending { it.createdAt }

            // Partial reads cannot repair an unknown corrupt collection. A complete server read can.
            val health = coordinator.ackCacheHealth[read.workId]
            val mayPersist = authoritative || stored !is Loaded.Failure && health?.requiresCompleteRepair != true
            val observedIds = observed.map { ListenerReviewCodec.documentId(it.workId, it.uid) }
            val repairsKnownAckRows = observedIds.any { it in health?.failedDocumentIds.orEmpty() }
            val needsWrite = authoritative || observed.isNotEmpty() &&
                (stored !is Loaded.Data || baseline.sortedByDescending { it.createdAt } != confirmed || repairsKnownAckRows)
            val written = if (needsWrite && mayPersist) persist(key, read.workId, confirmed) else !needsWrite
            if (needsWrite && written) {
                if (authoritative) {
                    coordinator.ackCacheHealth.remove(read.workId)
                    repairDeleteHealthAfterTruthWrite()
                } else repairAckCacheRows(read.workId, observedIds)
            }
            val unavailableBaseline = stored is Loaded.Failure && !authoritative
            val missingBehindPending = stored is Loaded.Missing && (frame.queryHasPendingWrites || frame.documents.any { it.hasPendingWrites })
            ReviewReadResult.Snapshot(
                confirmed = confirmed,
                pending = pending,
                fromCache = frame.fromCache,
                authoritative = authoritative,
                readFailed = invalidFrame || unavailableBaseline || missingBehindPending || !written ||
                    coordinator.ackCacheHealth.containsKey(read.workId) || coordinator.deleteCacheHealth.isNotEmpty()
            )
        }
    }

    private sealed interface Loaded {
        data object Missing : Loaded
        data class Data(val reviews: List<ListenerReview>) : Loaded
        data object Failure : Loaded
    }

    private fun load(key: String, workId: String): Loaded = try {
        when (val bytes = storage.read(key)) {
            ReviewSnapshotBytes.Missing -> Loaded.Missing
            ReviewSnapshotBytes.Failure -> Loaded.Failure
            is ReviewSnapshotBytes.Data -> Loaded.Data(decode(bytes.bytes, workId))
        }
    } catch (_: Exception) {
        Loaded.Failure
    }

    private fun persist(key: String, workId: String, reviews: List<ListenerReview>): Boolean = try {
        storage.write(key, encode(workId, reviews))
    } catch (_: Exception) {
        false
    }

    private fun workKey(workId: String): String {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { output ->
            output.writeNamespace()
            output.writeText(workId)
        }
        return MessageDigest.getInstance("SHA-256").digest(buffer.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun encode(workId: String, reviews: List<ListenerReview>): ByteArray {
        require(reviews.size <= MAX_ROWS)
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { output ->
            output.writeInt(MAGIC)
            output.writeInt(VERSION)
            output.writeNamespace()
            output.writeText(workId)
            output.writeInt(reviews.size)
            val ids = mutableSetOf<String>()
            reviews.forEach { review ->
                require(validReview(review, workId))
                val id = ListenerReviewCodec.documentId(review.workId, review.uid)
                require(ids.add(id))
                output.writeText(id)
                output.writeText(review.uid)
                output.writeText(review.authorName)
                output.writeInt(review.rating)
                output.writeOptional(review.body)
                output.writeOptional(review.editionTag)
                output.writeLong(review.createdAt)
                output.writeBoolean(review.editedAt != null)
                review.editedAt?.let(output::writeLong)
                require(buffer.size() <= MAX_BYTES)
            }
        }
        return buffer.toByteArray().also { require(it.size <= MAX_BYTES) }
    }

    private fun decode(bytes: ByteArray, workId: String): List<ListenerReview> {
        require(bytes.size <= MAX_BYTES)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == MAGIC && input.readInt() == VERSION)
            require(input.readNamespace() == namespace)
            require(input.readText() == workId)
            val count = input.readInt()
            require(count in 0..MAX_ROWS)
            val ids = mutableSetOf<String>()
            val reviews = List(count) {
                val id = input.readText()
                val review = ListenerReview(
                    workId = workId,
                    uid = input.readText(),
                    authorName = input.readText(),
                    rating = input.readInt(),
                    body = input.readOptional(),
                    editionTag = input.readOptional(),
                    createdAt = input.readLong(),
                    editedAt = if (input.readFlag()) input.readLong() else null
                )
                require(validReview(review, workId))
                require(id == ListenerReviewCodec.documentId(review.workId, review.uid) && ids.add(id))
                review
            }
            require(input.read() == -1)
            reviews
        }
    }

    private fun DataOutputStream.writeNamespace() {
        writeText(namespace.projectId)
        writeText(namespace.firebaseAppName)
        writeText(namespace.applicationId)
        writeText(namespace.databaseId)
    }
    private fun DataInputStream.readNamespace() = ReviewSnapshotNamespace(readText(), readText(), readText(), readText())

    private fun DataOutputStream.writeText(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES && bytes.toString(Charsets.UTF_8) == value)
        writeInt(bytes.size)
        write(bytes)
    }
    private fun DataInputStream.readText(): String {
        val count = readInt()
        require(count in 0..MAX_STRING_BYTES && count <= available())
        val bytes = ByteArray(count)
        readFully(bytes)
        val value = bytes.toString(Charsets.UTF_8)
        // Reject malformed UTF-8 rather than silently replacing bytes and changing identity.
        require(value.toByteArray(Charsets.UTF_8).contentEquals(bytes))
        return value
    }
    private fun DataOutputStream.writeOptional(value: String?) {
        writeBoolean(value != null)
        value?.let { writeText(it) }
    }
    private fun DataInputStream.readOptional(): String? = if (readFlag()) readText() else null
    private fun DataInputStream.readFlag(): Boolean {
        val value = readUnsignedByte()
        require(value in 0..1)
        return value == 1
    }

    private fun validReview(review: ListenerReview, workId: String): Boolean =
        review.workId == workId && ListenerReviewLimits.isWritable(review) &&
            review.authorName.length <= ListenerReviewLimits.MAX_AUTHOR_LEN &&
            review.body.let { it == null || it.isNotBlank() && it.length <= ListenerReviewLimits.MAX_BODY_LEN } &&
            review.editionTag.let { it == null || it.isNotBlank() && it.length <= ListenerReviewLimits.MAX_EDITION_TAG_LEN } &&
            review.uid.toByteArray(Charsets.UTF_8).size <= MAX_STRING_BYTES

    companion object {
        private const val MAGIC = 0x53524346
        private const val VERSION = 1
        private const val MAX_BYTES = 4 * 1024 * 1024
        private const val MAX_NAMESPACE_WORKS = 256
        private const val MAX_NAMESPACE_OPERATION_BYTES = 32L * 1024 * 1024
        private const val MAX_ROWS = 10_000
        private const val MAX_STRING_BYTES = 65_536
        private val coordinators = ConcurrentHashMap<CoordinatorKey, ReadCoordinator>()
        private val sharedReviewAcknowledgementScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
