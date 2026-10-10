// PRIVATE first vertical draft only. NOT APPLIED, NOT COMPILED, NOT RUN.
package com.slukhayka.audiobooks.data.reviews

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ConfirmedReviewSnapshotsTest {
    @Test
    fun pendingEditAfterFreshPolicyKeepsCompletePreviouslyConfirmedReview() = runBlocking {
        val root = Files.createTempDirectory("confirmed-review-public-seam-")
        try {
            val namespace = ReviewSnapshotNamespace("demo-slukhayka-acceptance", "host-public", "com.slukhayka.audiobooks")
            // Independent fixed full DTO; expected confirmed is never derived from pending replacement.
            val seed = ListenerReview(
                workId = "acceptance-work", uid = "listener-a", authorName = "Тестовий читач", rating = 3,
                body = "Початковий відгук", editionTag = "Тестове видання", createdAt = 100L, editedAt = null
            )
            val pending = ListenerReview(
                workId = "acceptance-work", uid = "listener-a", authorName = "Тестовий читач", rating = 5,
                body = "Переживає restart", editionTag = "Тестове видання", createdAt = 100L, editedAt = 200L
            )
            val documentId = "acceptance-work_listener-a"
            val first = ConfirmedReviewSnapshots(namespace, HostFileStorage(root))
            // DEFAULT initial seed must persist as a confirmed row without any drain proof/readiness callback.
            val seeded = first.project(first.beginRead("acceptance-work"), "listener-a", ReviewSnapshotFrame(
                documents = listOf(ReviewSnapshotDocument(documentId, seed, false)),
                fromCache = false, queryHasPendingWrites = false
            )) as ReviewReadResult.Snapshot
            assertEquals(listOf(seed), seeded.confirmed)
            assertEquals(emptyList<ListenerReview>(), seeded.pending)
            assertFalse(seeded.authoritative)
            assertFalse(seeded.readFailed)

            // Fresh objects, same actual file directory; no explicit shared coordinator injected by the test.
            val restarted = ConfirmedReviewSnapshots(namespace, HostFileStorage(root))
            val restored = restarted.project(restarted.beginRead("acceptance-work"), "listener-a", ReviewSnapshotFrame(
                documents = listOf(ReviewSnapshotDocument(documentId, pending, true)),
                fromCache = true, queryHasPendingWrites = true
            )) as ReviewReadResult.Snapshot
            assertEquals(listOf(seed), restored.confirmed)
            assertEquals(listOf(pending), restored.pending)
            assertFalse(restored.authoritative)
            assertFalse(restored.readFailed)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun otherListenerSeesPublicReviewsWithoutPrivatePendingOverlay() = runBlocking {
        val root = Files.createTempDirectory("confirmed-review-uid-seam-")
        try {
            val namespace = ReviewSnapshotNamespace("demo-slukhayka-acceptance", "host-uid", "com.slukhayka.audiobooks")
            val seedA = ListenerReview(
                workId = "uid-private-work", uid = "listener-a", authorName = "Читач A", rating = 3,
                body = "Публічний відгук A", editionTag = "Видання A", createdAt = 100L, editedAt = null
            )
            val pendingA = ListenerReview(
                workId = "uid-private-work", uid = "listener-a", authorName = "Читач A", rating = 5,
                body = "Приватна правка A", editionTag = "Видання A", createdAt = 100L, editedAt = 200L
            )
            val confirmedB = ListenerReview(
                workId = "uid-private-work", uid = "listener-b", authorName = "Читач B", rating = 4,
                body = "Відгук читача B", editionTag = "Видання B", createdAt = 90L, editedAt = null
            )
            val first = ConfirmedReviewSnapshots(namespace, HostFileStorage(root))
            val seeded = first.project(first.beginRead("uid-private-work"), "listener-a", ReviewSnapshotFrame(
                documents = listOf(ReviewSnapshotDocument("uid-private-work_listener-a", seedA, false)),
                fromCache = false, queryHasPendingWrites = false
            )) as ReviewReadResult.Snapshot
            assertEquals(listOf(seedA), seeded.confirmed)
            assertEquals(emptyList<ListenerReview>(), seeded.pending)
            assertFalse(seeded.readFailed)

            val forB = ConfirmedReviewSnapshots(namespace, HostFileStorage(root))
            val publicForB = forB.project(forB.beginRead("uid-private-work"), "listener-b", ReviewSnapshotFrame(
                documents = listOf(
                    ReviewSnapshotDocument("uid-private-work_listener-a", pendingA, true),
                    ReviewSnapshotDocument("uid-private-work_listener-b", confirmedB, false)
                ),
                fromCache = true, queryHasPendingWrites = true
            )) as ReviewReadResult.Snapshot
            assertEquals(listOf(seedA, confirmedB), publicForB.confirmed)
            assertEquals(emptyList<ListenerReview>(), publicForB.pending)
            assertFalse(publicForB.authoritative)
            assertFalse(publicForB.readFailed)

            val forA = ConfirmedReviewSnapshots(namespace, HostFileStorage(root))
            // B is absent from this SDK frame, so only real persisted public truth can restore it.
            val publicForA = forA.project(forA.beginRead("uid-private-work"), "listener-a", ReviewSnapshotFrame(
                documents = listOf(ReviewSnapshotDocument("uid-private-work_listener-a", pendingA, true)),
                fromCache = true, queryHasPendingWrites = true
            )) as ReviewReadResult.Snapshot
            assertEquals(listOf(seedA, confirmedB), publicForA.confirmed)
            assertEquals(listOf(pendingA), publicForA.pending)
            assertFalse(publicForA.authoritative)
            assertFalse(publicForA.readFailed)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun missingConfirmedFileWithPendingQueryCannotClaimCleanRead() = runBlocking {
        val root = Files.createTempDirectory("confirmed-review-missing-query-")
        try {
            val namespace = ReviewSnapshotNamespace("demo-slukhayka-acceptance", "host-pending-query", "com.slukhayka.audiobooks")
            val fresh = ConfirmedReviewSnapshots(namespace, HostFileStorage(root))
            val snapshot = fresh.project(fresh.beginRead("missing-confirmed-work"), "listener-a", ReviewSnapshotFrame(
                documents = emptyList(),
                fromCache = true,
                queryHasPendingWrites = true,
                origin = ReviewSnapshotOrigin.DEFAULT_OR_CACHE
            )) as ReviewReadResult.Snapshot
            assertEquals(emptyList<ListenerReview>(), snapshot.confirmed)
            assertEquals(emptyList<ListenerReview>(), snapshot.pending)
            assertEquals(emptySet<String>(), snapshot.deleting)
            assertFalse(snapshot.authoritative)
            assertEquals(true, snapshot.fromCache)
            assertEquals(true, snapshot.readFailed)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun onlyCompletePostDrainServerAbsencePersistsGenuineEmpty() = runBlocking {
        val root = Files.createTempDirectory("confirmed-review-authoritative-empty-")
        try {
            val namespace = ReviewSnapshotNamespace("demo-slukhayka-acceptance", "host-empty", "com.slukhayka.audiobooks")
            val seed = ListenerReview(
                workId = "authority-empty-work", uid = "listener-a", authorName = "Читач", rating = 3,
                body = "Повний публічний відгук", editionTag = "Видання", createdAt = 100L, editedAt = null
            )
            val first = ConfirmedReviewSnapshots(namespace, HostFileStorage(root))
            val seeded = first.project(first.beginRead("authority-empty-work"), "listener-a", ReviewSnapshotFrame(
                documents = listOf(ReviewSnapshotDocument("authority-empty-work_listener-a", seed, false)),
                fromCache = false, queryHasPendingWrites = false
            )) as ReviewReadResult.Snapshot
            assertEquals(listOf(seed), seeded.confirmed)
            assertFalse(seeded.readFailed)

            val local = ConfirmedReviewSnapshots(namespace, HostFileStorage(root))
            val absentLocally = local.project(local.beginRead("authority-empty-work"), "listener-a", ReviewSnapshotFrame(
                documents = emptyList(), fromCache = true, queryHasPendingWrites = false
            )) as ReviewReadResult.Snapshot
            assertEquals(listOf(seed), absentLocally.confirmed)
            assertEquals(emptyList<ListenerReview>(), absentLocally.pending)
            assertFalse(absentLocally.authoritative)
            assertFalse(absentLocally.readFailed)

            val server = ConfirmedReviewSnapshots(namespace, HostFileStorage(root))
            val genuineEmpty = server.project(requireNotNull(server.beginServerRead("authority-empty-work", server.beginDrain())), "listener-a", ReviewSnapshotFrame(
                documents = emptyList(), fromCache = false, queryHasPendingWrites = false,
                origin = ReviewSnapshotOrigin.POST_DRAIN_SERVER
            )) as ReviewReadResult.Snapshot
            assertEquals(emptyList<ListenerReview>(), genuineEmpty.confirmed)
            assertEquals(emptyList<ListenerReview>(), genuineEmpty.pending)
            assertEquals(true, genuineEmpty.authoritative)
            assertFalse(genuineEmpty.readFailed)

            val restarted = ConfirmedReviewSnapshots(namespace, HostFileStorage(root))
            // Pending query distinguishes a durable known-empty snapshot from an unknown missing file.
            val restoredEmpty = restarted.project(restarted.beginRead("authority-empty-work"), "listener-a", ReviewSnapshotFrame(
                documents = emptyList(), fromCache = true, queryHasPendingWrites = true
            )) as ReviewReadResult.Snapshot
            assertEquals(emptyList<ListenerReview>(), restoredEmpty.confirmed)
            assertEquals(emptyList<ListenerReview>(), restoredEmpty.pending)
            assertFalse(restoredEmpty.authoritative)
            assertFalse(restoredEmpty.readFailed)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun frameCapturedBeforeRejectionCannotBecomeAuthoritativeWhenProcessedLater() = runBlocking {
        val root = Files.createTempDirectory("confirmed-review-captured-frame-")
        try {
            val namespace = ReviewSnapshotNamespace("demo-slukhayka-acceptance", "host-capture", "com.slukhayka.audiobooks")
            val seed = ListenerReview(
                workId = "captured-frame-work", uid = "listener-a", authorName = "Читач", rating = 3,
                body = "Повний публічний відгук", editionTag = "Видання", createdAt = 100L, editedAt = null
            )
            val first = ConfirmedReviewSnapshots(namespace, HostFileStorage(root))
            val seeded = first.project(first.beginRead("captured-frame-work"), "listener-a", ReviewSnapshotFrame(
                documents = listOf(ReviewSnapshotDocument("captured-frame-work_listener-a", seed, false)),
                fromCache = false, queryHasPendingWrites = false
            )) as ReviewReadResult.Snapshot
            assertEquals(listOf(seed), seeded.confirmed)
            assertFalse(seeded.readFailed)

            val capturedBeforeRejection = ReviewSnapshotFrame(
                documents = emptyList(), fromCache = false, queryHasPendingWrites = false,
                origin = ReviewSnapshotOrigin.DEFAULT_OR_CACHE
            )
            // Pure frame ordering: the post-rejection server still has3. This is not an SDK barrier proof.
            val afterRejection = ConfirmedReviewSnapshots(namespace, HostFileStorage(root))
            val settled = afterRejection.project(requireNotNull(afterRejection.beginServerRead("captured-frame-work", afterRejection.beginDrain())), "listener-a", ReviewSnapshotFrame(
                documents = listOf(ReviewSnapshotDocument("captured-frame-work_listener-a", seed, false)),
                fromCache = false, queryHasPendingWrites = false, origin = ReviewSnapshotOrigin.POST_DRAIN_SERVER
            )) as ReviewReadResult.Snapshot
            assertEquals(listOf(seed), settled.confirmed)
            assertEquals(true, settled.authoritative)
            assertFalse(settled.readFailed)

            val later = ConfirmedReviewSnapshots(namespace, HostFileStorage(root))
            val processed = later.project(later.beginRead("captured-frame-work"), "listener-a", capturedBeforeRejection)
                as ReviewReadResult.Snapshot
            assertEquals(listOf(seed), processed.confirmed)
            assertEquals(emptyList<ListenerReview>(), processed.pending)
            assertFalse(processed.authoritative)
            assertFalse(processed.fromCache)
            assertFalse(processed.readFailed)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun ineligibleServerFramesNeverEraseLastGoodConfirmedReview() = runBlocking {
        val root = Files.createTempDirectory("confirmed-review-server-ineligible-")
        try {
            val namespace = ReviewSnapshotNamespace("demo-slukhayka-acceptance", "host-invalid", "com.slukhayka.audiobooks")
            val seed = ListenerReview(
                workId = "server-ineligible-work", uid = "listener-a", authorName = "Читач", rating = 3,
                body = "Повний публічний відгук", editionTag = "Видання", createdAt = 100L, editedAt = null
            )
            val document = ReviewSnapshotDocument("server-ineligible-work_listener-a", seed, false)
            val first = ConfirmedReviewSnapshots(namespace, HostFileStorage(root))
            val seeded = first.project(first.beginRead("server-ineligible-work"), "listener-a", ReviewSnapshotFrame(
                documents = listOf(document), fromCache = false, queryHasPendingWrites = false
            )) as ReviewReadResult.Snapshot
            assertEquals(listOf(seed), seeded.confirmed)
            assertFalse(seeded.readFailed)

            val ineligibleFrames = listOf(
                "cache" to ReviewSnapshotFrame(emptyList(), true, false, ReviewSnapshotOrigin.POST_DRAIN_SERVER),
                "query pending" to ReviewSnapshotFrame(emptyList(), false, true, ReviewSnapshotOrigin.POST_DRAIN_SERVER),
                "document pending" to ReviewSnapshotFrame(
                    listOf(ReviewSnapshotDocument("server-ineligible-work_listener-a", seed, true)),
                    false, false, ReviewSnapshotOrigin.POST_DRAIN_SERVER
                ),
                "invalid" to ReviewSnapshotFrame(
                    listOf(ReviewSnapshotDocument("server-ineligible-work_listener-a", null, false)),
                    false, false, ReviewSnapshotOrigin.POST_DRAIN_SERVER
                ),
                "duplicate" to ReviewSnapshotFrame(listOf(document, document), false, false, ReviewSnapshotOrigin.POST_DRAIN_SERVER),
                "id mismatch" to ReviewSnapshotFrame(
                    listOf(ReviewSnapshotDocument("server-ineligible-work_other", seed, false)),
                    false, false, ReviewSnapshotOrigin.POST_DRAIN_SERVER
                )
            )
            for ((label, frame) in ineligibleFrames) {
                val server = ConfirmedReviewSnapshots(namespace, HostFileStorage(root))
                assertEquals(label, ReviewReadResult.Failure, server.project(requireNotNull(server.beginServerRead("server-ineligible-work", server.beginDrain())), "listener-a", frame))
                val restarted = ConfirmedReviewSnapshots(namespace, HostFileStorage(root))
                val restored = restarted.project(restarted.beginRead("server-ineligible-work"), "listener-a", ReviewSnapshotFrame(
                    documents = emptyList(), fromCache = true, queryHasPendingWrites = false
                )) as ReviewReadResult.Snapshot
                assertEquals(label, listOf(seed), restored.confirmed)
                assertEquals(label, emptyList<ListenerReview>(), restored.pending)
                assertFalse(label, restored.authoritative)
                assertFalse(label, restored.readFailed)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun malformedFileReportsUnavailableConfirmedTruthWithoutInventingPreviousVote() = runBlocking {
        val root = Files.createTempDirectory("confirmed-review-malformed-")
        try {
            val namespace = ReviewSnapshotNamespace("demo-slukhayka-acceptance", "host-malformed", "com.slukhayka.audiobooks")
            val seed = ListenerReview(
                workId = "malformed-file-work", uid = "listener-a", authorName = "Читач A", rating = 3,
                body = "Початковий публічний відгук", editionTag = "Видання A", createdAt = 100L, editedAt = null
            )
            val pending = ListenerReview(
                workId = "malformed-file-work", uid = "listener-a", authorName = "Читач A", rating = 5,
                body = "Приватна правка", editionTag = "Видання A", createdAt = 100L, editedAt = 200L
            )
            val bytes = HostFileStorage(root)
            val first = ConfirmedReviewSnapshots(namespace, bytes)
            val seeded = first.project(first.beginRead("malformed-file-work"), "listener-a", ReviewSnapshotFrame(
                listOf(ReviewSnapshotDocument("malformed-file-work_listener-a", seed, false)), false, false
            )) as ReviewReadResult.Snapshot
            assertEquals(listOf(seed), seeded.confirmed)
            assertFalse(seeded.readFailed)
            // Input corruption at the approved public bytes boundary; result oracles stay at project.
            val key = bytes.keys()!!.single()
            assertEquals(true, bytes.write(key, byteArrayOf(0x7f, 0x00, 0x00, 0x00)))

            val restarted = ConfirmedReviewSnapshots(namespace, HostFileStorage(root))
            val degraded = restarted.project(restarted.beginRead("malformed-file-work"), "listener-a", ReviewSnapshotFrame(
                listOf(ReviewSnapshotDocument("malformed-file-work_listener-a", pending, true)), true, true
            )) as ReviewReadResult.Snapshot
            assertEquals(emptyList<ListenerReview>(), degraded.confirmed)
            assertEquals(listOf(pending), degraded.pending)
            assertFalse(degraded.authoritative)
            assertEquals(true, degraded.readFailed)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun validForeignNamespaceFileCannotSupplyThisWorksConfirmedReviews() = runBlocking {
        val root = Files.createTempDirectory("confirmed-review-foreign-namespace-")
        try {
            val ownNamespace = ReviewSnapshotNamespace("demo-slukhayka-acceptance", "host-own", "com.slukhayka.audiobooks")
            val foreignNamespace = ReviewSnapshotNamespace("demo-another-project", "host-foreign", "com.other.audiobooks")
            val ownSeed = ListenerReview(
                workId = "namespace-work", uid = "listener-a", authorName = "Читач A", rating = 3,
                body = "Власний публічний відгук", editionTag = "Видання A", createdAt = 100L, editedAt = null
            )
            val foreignSeed = ListenerReview(
                workId = "namespace-work", uid = "foreign-listener", authorName = "Чужий читач", rating = 5,
                body = "Відгук з іншого простору", editionTag = "Чуже видання", createdAt = 900L, editedAt = 950L
            )
            val bytes = HostFileStorage(root)
            val own = ConfirmedReviewSnapshots(ownNamespace, bytes)
            val ownResult = own.project(own.beginRead("namespace-work"), "listener-a", ReviewSnapshotFrame(
                listOf(ReviewSnapshotDocument("namespace-work_listener-a", ownSeed, false)), false, false
            )) as ReviewReadResult.Snapshot
            assertEquals(listOf(ownSeed), ownResult.confirmed)
            assertFalse(ownResult.readFailed)
            val ownKey = bytes.keys()!!.single()
            val foreign = ConfirmedReviewSnapshots(foreignNamespace, HostFileStorage(root))
            val foreignResult = foreign.project(foreign.beginRead("namespace-work"), "foreign-listener", ReviewSnapshotFrame(
                listOf(ReviewSnapshotDocument("namespace-work_foreign-listener", foreignSeed, false)), false, false
            )) as ReviewReadResult.Snapshot
            assertEquals(listOf(foreignSeed), foreignResult.confirmed)
            assertFalse(foreignResult.readFailed)
            // Transplant a valid but foreign envelope into the own cache key, without decoding internals.
            val foreignKey = bytes.keys()!!.single { it != ownKey }
            val foreignBytes = bytes.read(foreignKey) as ReviewSnapshotBytes.Data
            assertEquals(true, bytes.write(ownKey, foreignBytes.bytes))

            val restarted = ConfirmedReviewSnapshots(ownNamespace, HostFileStorage(root))
            val rejected = restarted.project(restarted.beginRead("namespace-work"), "listener-a", ReviewSnapshotFrame(
                emptyList(), true, false
            )) as ReviewReadResult.Snapshot
            assertEquals(emptyList<ListenerReview>(), rejected.confirmed)
            assertEquals(emptyList<ListenerReview>(), rejected.pending)
            assertFalse(rejected.authoritative)
            assertEquals(true, rejected.readFailed)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun unavailableReadKeepsSdkPendingHonestAndDoesNotOverwriteLastGoodFile() = runBlocking {
        val root = Files.createTempDirectory("confirmed-review-read-unavailable-")
        try {
            val namespace = ReviewSnapshotNamespace("demo-slukhayka-acceptance", "host-unavailable", "com.slukhayka.audiobooks")
            val seed = ListenerReview(
                workId = "unavailable-read-work", uid = "listener-a", authorName = "Читач A", rating = 3,
                body = "Початковий публічний відгук", editionTag = "Видання A", createdAt = 100L, editedAt = null
            )
            val pending = ListenerReview(
                workId = "unavailable-read-work", uid = "listener-a", authorName = "Читач A", rating = 5,
                body = "Приватна правка", editionTag = "Видання A", createdAt = 100L, editedAt = 200L
            )
            val bytes = HostFileStorage(root)
            val first = ConfirmedReviewSnapshots(namespace, bytes)
            val seeded = first.project(first.beginRead("unavailable-read-work"), "listener-a", ReviewSnapshotFrame(
                listOf(ReviewSnapshotDocument("unavailable-read-work_listener-a", seed, false)), false, false
            )) as ReviewReadResult.Snapshot
            assertEquals(listOf(seed), seeded.confirmed)
            assertFalse(seeded.readFailed)
            val backing = bytes
            val unavailable = object : ReviewSnapshotStorage {
                override val scopeId = backing.scopeId
                override fun read(key: String): ReviewSnapshotBytes = ReviewSnapshotBytes.Failure
                override fun write(key: String, bytes: ByteArray): Boolean = backing.write(key, bytes)
                override fun keys(): List<String>? = backing.keys()
            }
            val degraded = ConfirmedReviewSnapshots(namespace, unavailable)
            val unavailableResult = degraded.project(degraded.beginRead("unavailable-read-work"), "listener-a", ReviewSnapshotFrame(
                listOf(ReviewSnapshotDocument("unavailable-read-work_listener-a", pending, true)), true, true
            )) as ReviewReadResult.Snapshot
            assertEquals(emptyList<ListenerReview>(), unavailableResult.confirmed)
            assertEquals(listOf(pending), unavailableResult.pending)
            assertFalse(unavailableResult.authoritative)
            assertEquals(true, unavailableResult.readFailed)

            val restarted = ConfirmedReviewSnapshots(namespace, HostFileStorage(root))
            val restored = restarted.project(restarted.beginRead("unavailable-read-work"), "listener-a", ReviewSnapshotFrame(
                listOf(ReviewSnapshotDocument("unavailable-read-work_listener-a", pending, true)), true, true
            )) as ReviewReadResult.Snapshot
            assertEquals(listOf(seed), restored.confirmed)
            assertEquals(listOf(pending), restored.pending)
            assertFalse(restored.readFailed)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun failedWriteShowsObservedSdkTruthButPreservesOldFileUntilSuccessfulRetry() = runBlocking {
        val root = Files.createTempDirectory("confirmed-review-write-failure-")
        try {
            val namespace = ReviewSnapshotNamespace("demo-slukhayka-acceptance", "host-write-failed", "com.slukhayka.audiobooks")
            val seed = ListenerReview(
                workId = "failed-write-work", uid = "listener-a", authorName = "Читач A", rating = 3,
                body = "Початковий публічний відгук", editionTag = "Видання A", createdAt = 100L, editedAt = null
            )
            val observed = ListenerReview(
                workId = "failed-write-work", uid = "listener-a", authorName = "Читач A", rating = 5,
                body = "Новий підтверджений відгук", editionTag = "Видання A", createdAt = 100L, editedAt = 200L
            )
            val pendingNext = ListenerReview(
                workId = "failed-write-work", uid = "listener-a", authorName = "Читач A", rating = 4,
                body = "Наступна локальна правка", editionTag = "Видання A", createdAt = 100L, editedAt = 300L
            )
            val bytes = HostFileStorage(root)
            val first = ConfirmedReviewSnapshots(namespace, bytes)
            val seeded = first.project(first.beginRead("failed-write-work"), "listener-a", ReviewSnapshotFrame(
                listOf(ReviewSnapshotDocument("failed-write-work_listener-a", seed, false)), false, false
            )) as ReviewReadResult.Snapshot
            assertEquals(listOf(seed), seeded.confirmed)
            assertFalse(seeded.readFailed)
            val backing = bytes
            val failedWrites = object : ReviewSnapshotStorage {
                override val scopeId = backing.scopeId
                override fun read(key: String): ReviewSnapshotBytes = backing.read(key)
                override fun write(key: String, bytes: ByteArray): Boolean = false
                override fun keys(): List<String>? = backing.keys()
            }
            val degraded = ConfirmedReviewSnapshots(namespace, failedWrites)
            val observedResult = degraded.project(degraded.beginRead("failed-write-work"), "listener-a", ReviewSnapshotFrame(
                listOf(ReviewSnapshotDocument("failed-write-work_listener-a", observed, false)), true, false
            )) as ReviewReadResult.Snapshot
            assertEquals(listOf(observed), observedResult.confirmed)
            assertEquals(emptyList<ListenerReview>(), observedResult.pending)
            assertFalse(observedResult.authoritative)
            assertEquals(true, observedResult.readFailed)

            val unchangedFile = ConfirmedReviewSnapshots(namespace, HostFileStorage(root))
            val lastGood = unchangedFile.project(unchangedFile.beginRead("failed-write-work"), "listener-a", ReviewSnapshotFrame(
                emptyList(), true, false
            )) as ReviewReadResult.Snapshot
            assertEquals(listOf(seed), lastGood.confirmed)
            assertFalse(lastGood.readFailed)
            val retry = ConfirmedReviewSnapshots(namespace, HostFileStorage(root))
            val retried = retry.project(retry.beginRead("failed-write-work"), "listener-a", ReviewSnapshotFrame(
                listOf(ReviewSnapshotDocument("failed-write-work_listener-a", observed, false)), true, false
            )) as ReviewReadResult.Snapshot
            assertEquals(listOf(observed), retried.confirmed)
            assertFalse(retried.readFailed)
            val restarted = ConfirmedReviewSnapshots(namespace, HostFileStorage(root))
            val afterRetry = restarted.project(restarted.beginRead("failed-write-work"), "listener-a", ReviewSnapshotFrame(
                listOf(ReviewSnapshotDocument("failed-write-work_listener-a", pendingNext, true)), true, true
            )) as ReviewReadResult.Snapshot
            assertEquals(listOf(observed), afterRetry.confirmed)
            assertEquals(listOf(pendingNext), afterRetry.pending)
            assertFalse(afterRetry.readFailed)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun publishedReceiptWithoutAnotherReadPersistsConfirmedTruthBeforeNextPendingEdit() = runBlocking {
        val root = Files.createTempDirectory("confirmed-review-ack-no-refresh-")
        val processScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
        )
        try {
            val namespace = ReviewSnapshotNamespace("demo-slukhayka-acceptance", "host-ack-no-read", "com.slukhayka.audiobooks")
            val published = ListenerReview(
                workId = "ack-followup-work", uid = "listener-a", authorName = "Читач A", rating = 5,
                body = "Підтвердження без refresh", editionTag = "Видання A", createdAt = 100L, editedAt = 200L
            )
            val pendingNext = ListenerReview(
                workId = "ack-followup-work", uid = "listener-a", authorName = "Читач A", rating = 4,
                body = "Наступна локальна правка", editionTag = "Видання A", createdAt = 100L, editedAt = 300L
            )
            val first = ConfirmedReviewSnapshots(namespace, HostFileStorage(root), processScope)
            val acknowledgement = first.saveAcknowledgement(first.beginMutation("ack-followup-work_listener-a"), published)
            acknowledgement.backendSettled(true)
            assertEquals(ReviewRemoteResult.PUBLISHED, kotlinx.coroutines.withTimeout(10_000) {
                acknowledgement.receipt.awaitRemote()
            })
            // No project/server refresh/lastGood between the actual receipt verdict and next pending frame.
            val restarted = ConfirmedReviewSnapshots(namespace, HostFileStorage(root))
            val restored = restarted.project(restarted.beginRead("ack-followup-work"), "listener-a", ReviewSnapshotFrame(
                listOf(ReviewSnapshotDocument("ack-followup-work_listener-a", pendingNext, true)), true, true
            )) as ReviewReadResult.Snapshot
            assertEquals(listOf(published), restored.confirmed)
            assertEquals(listOf(pendingNext), restored.pending)
            assertFalse(restored.authoritative)
            assertFalse(restored.readFailed)
        } finally {
            processScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun cancelledScreenWaiterCannotPublishBeforeHeldCacheAttemptCompletes() = runBlocking {
        val root = Files.createTempDirectory("confirmed-review-held-producer-")
        val dispatcher = HeldAcknowledgementDispatcher()
        val processScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + dispatcher)
        val enteredWrite = java.util.concurrent.CountDownLatch(1)
        val releaseWrite = java.util.concurrent.CountDownLatch(1)
        val attemptFinished = java.util.concurrent.atomic.AtomicBoolean(false)
        val written = java.util.concurrent.atomic.AtomicBoolean(false)
        var worker: Thread? = null
        try {
            val namespace = ReviewSnapshotNamespace("demo-slukhayka-acceptance", "host-held-ack", "com.slukhayka.audiobooks")
            val published = ListenerReview(
                workId = "held-ack-work", uid = "listener-a", authorName = "Читач A", rating = 5,
                body = "Прийнятий відгук", editionTag = "Видання A", createdAt = 100L, editedAt = 200L
            )
            val pendingNext = ListenerReview(
                workId = "held-ack-work", uid = "listener-a", authorName = "Читач A", rating = 4,
                body = "Наступна правка", editionTag = "Видання A", createdAt = 100L, editedAt = 300L
            )
            val bytes = HostFileStorage(root)
            val heldBytes = object : ReviewSnapshotStorage by bytes {
                override fun write(key: String, content: ByteArray): Boolean {
                    enteredWrite.countDown()
                    check(releaseWrite.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    val result = bytes.write(key, content)
                    written.set(result)
                    attemptFinished.set(true)
                    return result
                }
            }
            val policy = ConfirmedReviewSnapshots(namespace, heldBytes, processScope)
            val ack = policy.saveAcknowledgement(policy.beginMutation("held-ack-work_listener-a"), published)
            ack.backendSettled(true)
            // Cancel the caller while the independent producer is still queued, before any disk attempt.
            val screenWaiter = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                ack.receipt.awaitRemote()
            }
            screenWaiter.cancel()
            screenWaiter.join()
            assertFalse(attemptFinished.get())
            val activeWorker = Thread({ dispatcher.runQueued() }, "confirmed-review-held-ack-test").also { it.start() }
            worker = activeWorker
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                check(enteredWrite.await(10, java.util.concurrent.TimeUnit.SECONDS))
            }
            // The actual write is now held. Public receipt must still be pending, regardless of UI cancellation.
            assertEquals(null, kotlinx.coroutines.withTimeoutOrNull(100) { ack.receipt.awaitRemote() })
            assertFalse(attemptFinished.get())
            releaseWrite.countDown()
            assertEquals(ReviewRemoteResult.PUBLISHED, kotlinx.coroutines.withTimeout(10_000) {
                ack.receipt.awaitRemote()
            })
            org.junit.Assert.assertTrue(attemptFinished.get())
            org.junit.Assert.assertTrue(written.get())
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { activeWorker.join(10_000) }
            assertFalse(activeWorker.isAlive)
            val restarted = ConfirmedReviewSnapshots(namespace, HostFileStorage(root))
            val snapshot = restarted.project(restarted.beginRead("held-ack-work"), "listener-a", ReviewSnapshotFrame(
                listOf(ReviewSnapshotDocument("held-ack-work_listener-a", pendingNext, true)), true, true
            )) as ReviewReadResult.Snapshot
            assertEquals(listOf(published), snapshot.confirmed)
            assertEquals(listOf(pendingNext), snapshot.pending)
            assertFalse(snapshot.authoritative)
            assertFalse(snapshot.readFailed)
        } finally {
            releaseWrite.countDown()
            processScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            worker?.let {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { it.join(10_000) }
                check(!it.isAlive)
            }
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun backendRejectionPerformsNoCacheIoAndPreservesConfirmedPublicRows() = runBlocking {
        val root = Files.createTempDirectory("confirmed-review-rejected-ack-")
        val processScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        try {
            val namespace = ReviewSnapshotNamespace("demo-slukhayka-acceptance", "host-rejected-ack", "com.slukhayka.audiobooks")
            val seed = ListenerReview("reject-ack-work", "listener-a", "Читач A", 3, "Початковий", "Видання A", 100L, null)
            val rejected = ListenerReview("reject-ack-work", "listener-a", "Читач A", 5, "Відхилений", "Видання A", 100L, 200L)
            val bytes = HostFileStorage(root)
            val seedPolicy = ConfirmedReviewSnapshots(namespace, bytes)
            seedPolicy.project(seedPolicy.beginRead("reject-ack-work"), "listener-a", ReviewSnapshotFrame(
                listOf(ReviewSnapshotDocument("reject-ack-work_listener-a", seed, false)), false, false
            ))
            val io = java.util.concurrent.atomic.AtomicInteger()
            val counted = object : ReviewSnapshotStorage by bytes {
                override fun read(key: String): ReviewSnapshotBytes { io.incrementAndGet(); return bytes.read(key) }
                override fun write(key: String, content: ByteArray): Boolean { io.incrementAndGet(); return bytes.write(key, content) }
                override fun keys(): List<String>? { io.incrementAndGet(); return bytes.keys() }
            }
            val policy = ConfirmedReviewSnapshots(namespace, counted, processScope)
            val ack = policy.saveAcknowledgement(policy.beginMutation("reject-ack-work_listener-a"), rejected)
            ack.backendSettled(false)
            assertEquals(ReviewRemoteResult.FAILED, kotlinx.coroutines.withTimeout(10_000) { ack.receipt.awaitRemote() })
            assertEquals(0, io.get())
            val restarted = ConfirmedReviewSnapshots(namespace, HostFileStorage(root))
            val snapshot = restarted.project(restarted.beginRead("reject-ack-work"), "listener-a", ReviewSnapshotFrame(emptyList(), true, false)) as ReviewReadResult.Snapshot
            assertEquals(listOf(seed), snapshot.confirmed)
            assertEquals(emptyList<ListenerReview>(), snapshot.pending)
            assertFalse(snapshot.authoritative)
            assertFalse(snapshot.readFailed)
        } finally {
            processScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun failedAcceptedWriteRemainsPublishedAndOnlyActualMatchingTruthRepairsHealth() = runBlocking {
        val root = Files.createTempDirectory("confirmed-review-ack-health-")
        val processScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        try {
            val namespace = ReviewSnapshotNamespace("demo-slukhayka-acceptance", "host-ack-health", "com.slukhayka.audiobooks")
            val seedA = ListenerReview("ack-health-work", "listener-a", "Читач A", 3, "Початковий A", "Видання A", 100L, null)
            val publicB = ListenerReview("ack-health-work", "listener-b", "Читач B", 4, "Публічний B", "Видання B", 200L, null)
            val publicC = ListenerReview("ack-health-work", "listener-c", "Читач C", 2, "Публічний C", "Видання C", 300L, null)
            val publishedA = ListenerReview("ack-health-work", "listener-a", "Читач A", 5, "Прийнятий A", "Видання A", 100L, 200L)
            val pendingNext = ListenerReview("ack-health-work", "listener-a", "Читач A", 4, "Наступна правка A", "Видання A", 100L, 300L)
            val bytes = HostFileStorage(root)
            val seedPolicy = ConfirmedReviewSnapshots(namespace, bytes)
            seedPolicy.project(seedPolicy.beginRead("ack-health-work"), "listener-a", ReviewSnapshotFrame(listOf(
                ReviewSnapshotDocument("ack-health-work_listener-a", seedA, false),
                ReviewSnapshotDocument("ack-health-work_listener-b", publicB, false)
            ), false, false))
            val attempts = java.util.concurrent.atomic.AtomicInteger()
            val failing = object : ReviewSnapshotStorage by bytes {
                override fun write(key: String, content: ByteArray): Boolean { attempts.incrementAndGet(); return false }
            }
            val writer = ConfirmedReviewSnapshots(namespace, failing, processScope)
            val ack = writer.saveAcknowledgement(writer.beginMutation("ack-health-work_listener-a"), publishedA)
            ack.backendSettled(true)
            assertEquals(ReviewRemoteResult.PUBLISHED, kotlinx.coroutines.withTimeout(10_000) { ack.receipt.awaitRemote() })
            assertEquals(1, attempts.get())
            val restarted = ConfirmedReviewSnapshots(namespace, HostFileStorage(root))
            val pending = restarted.project(restarted.beginRead("ack-health-work"), "listener-a", ReviewSnapshotFrame(
                listOf(ReviewSnapshotDocument("ack-health-work_listener-a", pendingNext, true)), true, true
            )) as ReviewReadResult.Snapshot
            assertEquals(listOf(publicB, seedA), pending.confirmed)
            assertEquals(listOf(pendingNext), pending.pending)
            org.junit.Assert.assertTrue(pending.readFailed)
            restarted.beginMutation("ack-health-work_listener-a")
            val empty = restarted.project(restarted.beginRead("ack-health-work"), "listener-a", ReviewSnapshotFrame(emptyList(), true, false)) as ReviewReadResult.Snapshot
            assertEquals(listOf(publicB, seedA), empty.confirmed)
            org.junit.Assert.assertTrue(empty.readFailed)
            val unrelated = restarted.project(restarted.beginRead("ack-health-work"), "listener-a", ReviewSnapshotFrame(
                listOf(ReviewSnapshotDocument("ack-health-work_listener-c", publicC, false)), true, false
            )) as ReviewReadResult.Snapshot
            assertEquals(listOf(publicC, publicB, seedA), unrelated.confirmed)
            org.junit.Assert.assertTrue(unrelated.readFailed)
            val repaired = restarted.project(restarted.beginRead("ack-health-work"), "listener-a", ReviewSnapshotFrame(
                listOf(ReviewSnapshotDocument("ack-health-work_listener-a", publishedA, false)), true, false
            )) as ReviewReadResult.Snapshot
            assertEquals(listOf(publicC, publicB, publishedA), repaired.confirmed)
            assertFalse(repaired.readFailed)
            val fresh = ConfirmedReviewSnapshots(namespace, HostFileStorage(root))
            val durable = fresh.project(fresh.beginRead("ack-health-work"), "listener-a", ReviewSnapshotFrame(
                listOf(ReviewSnapshotDocument("ack-health-work_listener-a", pendingNext, true)), true, true
            )) as ReviewReadResult.Snapshot
            assertEquals(listOf(publicC, publicB, publishedA), durable.confirmed)
            assertEquals(listOf(pendingNext), durable.pending)
            assertFalse(durable.readFailed)
        } finally {
            processScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun corruptOrUnavailableAckBaselineRequiresCompleteTruthWithoutDroppingOtherPublicRows() = runBlocking {
        for (unavailable in listOf(false, true)) {
            val root = Files.createTempDirectory("confirmed-review-ack-unknown-")
            val processScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
            try {
                val namespace = ReviewSnapshotNamespace("demo-slukhayka-acceptance", "host-unknown-$unavailable", "com.slukhayka.audiobooks")
                val seedA = ListenerReview("ack-unknown-work", "listener-a", "Читач A", 3, "Початковий A", "Видання A", 100L, null)
                val publicB = ListenerReview("ack-unknown-work", "listener-b", "Читач B", 4, "Публічний B", "Видання B", 200L, null)
                val publishedA = ListenerReview("ack-unknown-work", "listener-a", "Читач A", 5, "Прийнятий A", "Видання A", 100L, 200L)
                val pendingNext = ListenerReview("ack-unknown-work", "listener-a", "Читач A", 4, "Наступна правка A", "Видання A", 100L, 300L)
                val bytes = HostFileStorage(root)
                val seedPolicy = ConfirmedReviewSnapshots(namespace, bytes)
                seedPolicy.project(seedPolicy.beginRead("ack-unknown-work"), "listener-a", ReviewSnapshotFrame(listOf(
                    ReviewSnapshotDocument("ack-unknown-work_listener-a", seedA, false),
                    ReviewSnapshotDocument("ack-unknown-work_listener-b", publicB, false)
                ), false, false))
                if (!unavailable) org.junit.Assert.assertTrue(bytes.write(bytes.keys()!!.single(), byteArrayOf(0, 1, 2)))
                val writes = java.util.concurrent.atomic.AtomicInteger()
                val faulty = object : ReviewSnapshotStorage by bytes {
                    override fun read(key: String): ReviewSnapshotBytes = if (unavailable) ReviewSnapshotBytes.Failure else bytes.read(key)
                    override fun write(key: String, content: ByteArray): Boolean { writes.incrementAndGet(); return bytes.write(key, content) }
                }
                val writer = ConfirmedReviewSnapshots(namespace, faulty, processScope)
                val ack = writer.saveAcknowledgement(writer.beginMutation("ack-unknown-work_listener-a"), publishedA)
                ack.backendSettled(true)
                assertEquals(ReviewRemoteResult.PUBLISHED, kotlinx.coroutines.withTimeout(10_000) { ack.receipt.awaitRemote() })
                assertEquals(0, writes.get())
                val restarted = ConfirmedReviewSnapshots(namespace, HostFileStorage(root))
                val old = restarted.project(restarted.beginRead("ack-unknown-work"), "listener-a", ReviewSnapshotFrame(
                    listOf(ReviewSnapshotDocument("ack-unknown-work_listener-a", pendingNext, true)), true, true
                )) as ReviewReadResult.Snapshot
                assertEquals(if (unavailable) listOf(publicB, seedA) else emptyList<ListenerReview>(), old.confirmed)
                assertEquals(listOf(pendingNext), old.pending)
                org.junit.Assert.assertTrue(old.readFailed)
                val partial = restarted.project(restarted.beginRead("ack-unknown-work"), "listener-a", ReviewSnapshotFrame(
                    listOf(ReviewSnapshotDocument("ack-unknown-work_listener-a", publishedA, false)), true, false
                )) as ReviewReadResult.Snapshot
                assertEquals(if (unavailable) listOf(publicB, publishedA) else listOf(publishedA), partial.confirmed)
                org.junit.Assert.assertTrue(partial.readFailed)
                val repairFails = ConfirmedReviewSnapshots(namespace, object : ReviewSnapshotStorage by bytes {
                    override fun write(key: String, content: ByteArray): Boolean = false
                })
                val failedComplete = repairFails.project(requireNotNull(repairFails.beginServerRead("ack-unknown-work", repairFails.beginDrain())), "listener-a", ReviewSnapshotFrame(listOf(
                    ReviewSnapshotDocument("ack-unknown-work_listener-a", publishedA, false),
                    ReviewSnapshotDocument("ack-unknown-work_listener-b", publicB, false)
                ), false, false, ReviewSnapshotOrigin.POST_DRAIN_SERVER)) as ReviewReadResult.Snapshot
                assertEquals(listOf(publicB, publishedA), failedComplete.confirmed)
                org.junit.Assert.assertTrue(failedComplete.readFailed)
                val stillUnavailable = restarted.project(restarted.beginRead("ack-unknown-work"), "listener-a", ReviewSnapshotFrame(
                    listOf(ReviewSnapshotDocument("ack-unknown-work_listener-a", pendingNext, true)), true, true
                )) as ReviewReadResult.Snapshot
                assertEquals(if (unavailable) listOf(publicB, seedA) else emptyList<ListenerReview>(), stillUnavailable.confirmed)
                org.junit.Assert.assertTrue(stillUnavailable.readFailed)
                val complete = restarted.project(requireNotNull(restarted.beginServerRead("ack-unknown-work", restarted.beginDrain())), "listener-a", ReviewSnapshotFrame(listOf(
                    ReviewSnapshotDocument("ack-unknown-work_listener-a", publishedA, false),
                    ReviewSnapshotDocument("ack-unknown-work_listener-b", publicB, false)
                ), false, false, ReviewSnapshotOrigin.POST_DRAIN_SERVER)) as ReviewReadResult.Snapshot
                assertEquals(listOf(publicB, publishedA), complete.confirmed)
                org.junit.Assert.assertTrue(complete.authoritative)
                assertFalse(complete.readFailed)
                val fresh = ConfirmedReviewSnapshots(namespace, HostFileStorage(root))
                val durable = fresh.project(fresh.beginRead("ack-unknown-work"), "listener-a", ReviewSnapshotFrame(
                    listOf(ReviewSnapshotDocument("ack-unknown-work_listener-a", pendingNext, true)), true, true
                )) as ReviewReadResult.Snapshot
                assertEquals(listOf(publicB, publishedA), durable.confirmed)
                assertEquals(listOf(pendingNext), durable.pending)
                assertFalse(durable.readFailed)
            } finally {
                processScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
                root.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun independentInstancesRespectAcceptedOrderAndRejectCapturedStaleReads() = runBlocking {
        for (scenario in listOf("accepted-newer", "queued-newer", "failed-newer")) {
            val root = Files.createTempDirectory("confirmed-review-shared-ack-order-")
            val processScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
            try {
                val namespace = ReviewSnapshotNamespace("demo-slukhayka-acceptance", "host-order-$scenario", "com.slukhayka.audiobooks")
                val seed = ListenerReview("ack-order-work", "listener-a", "Читач A", 3, "Початковий", "Видання A", 100L, null)
                val accepted5 = ListenerReview("ack-order-work", "listener-a", "Читач A", 5, "Прийнята правка5", "Видання A", 100L, 200L)
                val accepted6 = ListenerReview("ack-order-work", "listener-a", "Читач A", 4, "Прийнята правка6", "Видання A", 100L, 150L)
                val pendingNext = ListenerReview("ack-order-work", "listener-a", "Читач A", 2, "Наступна правка7", "Видання A", 100L, 400L)
                val seedBytes = HostFileStorage(root)
                val seedPolicy = ConfirmedReviewSnapshots(namespace, seedBytes)
                seedPolicy.project(seedPolicy.beginRead("ack-order-work"), "listener-a", ReviewSnapshotFrame(
                    listOf(ReviewSnapshotDocument("ack-order-work_listener-a", seed, false)), false, false
                ))
                val oldIo = java.util.concurrent.atomic.AtomicInteger()
                val oldBytes = object : ReviewSnapshotStorage by HostFileStorage(root) {
                    private val actual = HostFileStorage(root)
                    override fun read(key: String): ReviewSnapshotBytes { oldIo.incrementAndGet(); return actual.read(key) }
                    override fun write(key: String, content: ByteArray): Boolean { oldIo.incrementAndGet(); return actual.write(key, content) }
                    override fun keys(): List<String>? { oldIo.incrementAndGet(); return actual.keys() }
                }
                val older = ConfirmedReviewSnapshots(namespace, oldBytes, processScope)
                val newerWrites = java.util.concurrent.atomic.AtomicInteger()
                val newerActual = HostFileStorage(root)
                val newerBytes = object : ReviewSnapshotStorage by newerActual {
                    override fun write(key: String, content: ByteArray): Boolean {
                        newerWrites.incrementAndGet()
                        return scenario != "failed-newer" && newerActual.write(key, content)
                    }
                }
                val newer = ConfirmedReviewSnapshots(namespace, newerBytes, processScope)
                val ack5 = older.saveAcknowledgement(older.beginMutation("ack-order-work_listener-a"), accepted5)
                val ack6 = newer.saveAcknowledgement(newer.beginMutation("ack-order-work_listener-a"), accepted6)
                val captured = requireNotNull(newer.beginServerRead("ack-order-work", newer.beginDrain()))
                val capturedFrame = ReviewSnapshotFrame(listOf(
                    ReviewSnapshotDocument("ack-order-work_listener-a", seed, false)
                ), false, false, ReviewSnapshotOrigin.POST_DRAIN_SERVER)
                if (scenario != "queued-newer") {
                    ack6.backendSettled(true)
                    assertEquals(ReviewRemoteResult.PUBLISHED, kotlinx.coroutines.withTimeout(10_000) { ack6.receipt.awaitRemote() })
                    assertEquals(1, newerWrites.get())
                }
                ack5.backendSettled(true)
                assertEquals(ReviewRemoteResult.PUBLISHED, kotlinx.coroutines.withTimeout(10_000) { ack5.receipt.awaitRemote() })
                if (scenario != "queued-newer") assertEquals(0, oldIo.get())
                else assertEquals(0, newerWrites.get())
                assertEquals(ReviewReadResult.Failure, newer.project(captured, "listener-a", capturedFrame))
                val fresh = ConfirmedReviewSnapshots(namespace, HostFileStorage(root))
                val pendingDto = if (scenario == "queued-newer") accepted6 else pendingNext
                val result = fresh.project(fresh.beginRead("ack-order-work"), "listener-a", ReviewSnapshotFrame(
                    listOf(ReviewSnapshotDocument("ack-order-work_listener-a", pendingDto, true)), true, true
                )) as ReviewReadResult.Snapshot
                val literalExpected = when (scenario) {
                    "accepted-newer" -> accepted6
                    "queued-newer" -> accepted5
                    else -> seed
                }
                assertEquals(listOf(literalExpected), result.confirmed)
                assertEquals(listOf(pendingDto), result.pending)
                assertFalse(result.authoritative)
                assertEquals(scenario == "failed-newer", result.readFailed)
            } finally {
                processScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
                root.toFile().deleteRecursively()
            }
        }
    }

    // PRIVATE additional oracles, NOT APPLIED / COMPILED / RUN.
    @Test
    fun callerServerLabelCannotPromoteOrdinaryReadIntoAbsenceAuthority() = runBlocking {
        val root = Files.createTempDirectory("confirmed-untrusted-server-label-")
        try {
            val namespace = ReviewSnapshotNamespace("demo-slukhayka-acceptance", "host-untrusted-label", "com.slukhayka.audiobooks")
            val seed = ListenerReview("label-work", "listener-a", "Читач A", 3, "Повний початковий", "Видання A", 100L, null)
            val first = ConfirmedReviewSnapshots(namespace, HostFileStorage(root))
            val seeded = first.project(first.beginRead("label-work"), "listener-a", ReviewSnapshotFrame(
                listOf(ReviewSnapshotDocument("label-work_listener-a", seed, false)), false, false
            )) as ReviewReadResult.Snapshot
            assertEquals(listOf(seed), seeded.confirmed)
            val caller = ConfirmedReviewSnapshots(namespace, HostFileStorage(root))
            val rejected = caller.project(caller.beginRead("label-work"), "listener-a", ReviewSnapshotFrame(
                emptyList(), false, false, ReviewSnapshotOrigin.POST_DRAIN_SERVER
            ))
            assertEquals(ReviewReadResult.Failure, rejected)
            val fresh = ConfirmedReviewSnapshots(namespace, HostFileStorage(root))
            val truth = fresh.project(fresh.beginRead("label-work"), "listener-a", ReviewSnapshotFrame(emptyList(), true, false)) as ReviewReadResult.Snapshot
            assertEquals(listOf(seed), truth.confirmed)
            assertEquals(emptyList<ListenerReview>(), truth.pending)
            assertFalse(truth.authoritative)
            assertFalse(truth.readFailed)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun acceptedDeleteWithTooManyKeysPerformsNoPayloadReadOrWrite() = runBlocking {
        val root = Files.createTempDirectory("confirmed-delete-key-budget-")
        try {
            val namespace = ReviewSnapshotNamespace("demo-slukhayka-acceptance", "host-key-budget", "com.slukhayka.audiobooks")
            val seed = ListenerReview("budget-work", "listener-a", "Читач A", 3, "Повний початковий", "Видання A", 100L, null)
            val bytes = HostFileStorage(root)
            val first = ConfirmedReviewSnapshots(namespace, bytes)
            val seeded = first.project(first.beginRead("budget-work"), "listener-a", ReviewSnapshotFrame(
                listOf(ReviewSnapshotDocument("budget-work_listener-a", seed, false)), false, false
            )) as ReviewReadResult.Snapshot
            assertEquals(listOf(seed), seeded.confirmed)
            val reads = java.util.concurrent.atomic.AtomicInteger()
            val writes = java.util.concurrent.atomic.AtomicInteger()
            val keyCalls = java.util.concurrent.atomic.AtomicInteger()
            val manyKeys = object : ReviewSnapshotStorage by bytes {
                override fun keys(): List<String> {
                    keyCalls.incrementAndGet()
                    return (bytes.keys().orEmpty() + (0 until 256).map { "%064x".format(it) }).distinct()
                }
                override fun read(key: String): ReviewSnapshotBytes { reads.incrementAndGet(); return bytes.read(key) }
                override fun write(key: String, content: ByteArray): Boolean { writes.incrementAndGet(); return bytes.write(key, content) }
            }
            val policy = ConfirmedReviewSnapshots(namespace, manyKeys)
            val ack = policy.deleteAcknowledgement(policy.beginMutation("budget-work_listener-a"))
            ack.backendSettled(true)
            assertEquals(true, kotlinx.coroutines.withTimeout(10_000) { ack.receipt.awaitRemote() })
            assertEquals(1, keyCalls.get())
            assertEquals(0, reads.get())
            assertEquals(0, writes.get())
            val fresh = ConfirmedReviewSnapshots(namespace, HostFileStorage(root))
            val truth = fresh.project(fresh.beginRead("budget-work"), "listener-a", ReviewSnapshotFrame(emptyList(), true, false)) as ReviewReadResult.Snapshot
            assertEquals(listOf(seed), truth.confirmed)
            assertEquals(emptyList<ListenerReview>(), truth.pending)
            org.junit.Assert.assertTrue(truth.readFailed)
            assertFalse(truth.authoritative)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun acceptedDeleteReservesMatchedRereadWriteAndReadBackBeforeAnyWrite() = runBlocking {
        val root = Files.createTempDirectory("confirmed-delete-byte-reservation-")
        try {
            val namespace = ReviewSnapshotNamespace("demo-slukhayka-acceptance", "host-byte-budget", "com.slukhayka.audiobooks")
            val seedA = ListenerReview("budget_work", "a", "Читач A", 3, "Початковий A", "Видання A", 100L, null)
            // Two distinct known Work identities share this exact document-id string; never parse it.
            val seedB = ListenerReview("budget", "work_a", "Читач B", 4, "Початковий B", "Видання B", 90L, null)
            assertEquals("budget_work_a", ListenerReviewCodec.documentId(seedA.workId, seedA.uid))
            assertEquals("budget_work_a", ListenerReviewCodec.documentId(seedB.workId, seedB.uid))
            val workIds = listOf("budget_work", "budget", "unrelated-one", "unrelated-two")
            val expected = workIds.associateWith { workId ->
                val literalRows = (0 until 1850).map { index -> ListenerReview(
                    workId, "public-$index", "Ресурсний тест", 2, "x".repeat(1800), "Публічне видання", 1000L + index, null
                ) }
                (literalRows + when (workId) { "budget_work" -> listOf(seedA); "budget" -> listOf(seedB); else -> emptyList() })
                    .sortedByDescending { it.createdAt }
            }
            val bytes = HostFileStorage(root)
            val first = ConfirmedReviewSnapshots(namespace, bytes)
            for (workId in workIds) {
                val rows = expected.getValue(workId)
                val seeded = first.project(first.beginRead(workId), "a", ReviewSnapshotFrame(rows.map {
                    ReviewSnapshotDocument(ListenerReviewCodec.documentId(it.workId, it.uid), it, false)
                }, false, false)) as ReviewReadResult.Snapshot
                assertEquals(rows, seeded.confirmed)
                assertFalse(seeded.readFailed)
            }
            // File sizes are a resource precondition, never the returned-review oracle.
            val sizes = Files.list(root).use { files -> files.mapToLong { Files.size(it) }.toArray().toList() }
            assertEquals(4, sizes.size)
            org.junit.Assert.assertTrue(sizes.all { it <= 4L * 1024 * 1024 })
            val total = sizes.sum()
            org.junit.Assert.assertTrue(total <= 32L * 1024 * 1024)
            org.junit.Assert.assertTrue(total + 6L * sizes.minOrNull()!! > 32L * 1024 * 1024)
            val reads = java.util.concurrent.atomic.AtomicInteger()
            val writes = java.util.concurrent.atomic.AtomicInteger()
            val counted = object : ReviewSnapshotStorage by bytes {
                override fun read(key: String): ReviewSnapshotBytes { reads.incrementAndGet(); return bytes.read(key) }
                override fun write(key: String, content: ByteArray): Boolean { writes.incrementAndGet(); return bytes.write(key, content) }
            }
            val policy = ConfirmedReviewSnapshots(namespace, counted)
            val ack = policy.deleteAcknowledgement(policy.beginMutation("budget_work_a"))
            ack.backendSettled(true)
            assertEquals(true, kotlinx.coroutines.withTimeout(10_000) { ack.receipt.awaitRemote() })
            assertEquals(4, reads.get())
            assertEquals(0, writes.get())
            for (workId in workIds) {
                val fresh = ConfirmedReviewSnapshots(namespace, HostFileStorage(root))
                val truth = fresh.project(fresh.beginRead(workId), "a", ReviewSnapshotFrame(emptyList(), true, false)) as ReviewReadResult.Snapshot
                assertEquals(expected.getValue(workId), truth.confirmed)
                assertEquals(emptyList<ListenerReview>(), truth.pending)
                org.junit.Assert.assertTrue(truth.readFailed)
                assertFalse(truth.authoritative)
            }
        } finally { root.toFile().deleteRecursively() }
    }

    // PRIVATE runnable public oracles, NOT APPLIED / COMPILED / RUN.
    @Test
    fun heldSubmissionAttachesSignalBeforeConcurrentDrainCanCapture() = runBlocking {
        val root = Files.createTempDirectory("confirmed-held-enqueue-")
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val drainAttempt = java.util.concurrent.CountDownLatch(1)
        val order = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val error = java.util.concurrent.atomic.AtomicReference<Throwable>()
        val ack = java.util.concurrent.atomic.AtomicReference<ReviewSaveAcknowledgement>()
        val drain = java.util.concurrent.atomic.AtomicReference<ReviewSnapshotDrainToken>()
        val task = com.google.android.gms.tasks.TaskCompletionSource<Unit>()
        var submitter: Thread? = null
        var reader: Thread? = null
        try {
            val namespace = ReviewSnapshotNamespace("demo-slukhayka-acceptance", "host-held-submission", "com.slukhayka.audiobooks")
            val seed = ListenerReview("held-work", "a", "Читач A", 3, "Початковий", "Видання A", 100L, null)
            val edit = ListenerReview("held-work", "a", "Читач A", 5, "Прийнята правка", "Видання A", 100L, 200L)
            val bytes = HostFileStorage(root)
            val policy = ConfirmedReviewSnapshots(namespace, bytes)
            val seeded = policy.project(policy.beginRead("held-work"), "a", ReviewSnapshotFrame(listOf(
                ReviewSnapshotDocument("held-work_a", seed, false)), false, false)) as ReviewReadResult.Snapshot
            assertEquals(listOf(seed), seeded.confirmed)
            submitter = Thread({
                try {
                    policy.enqueueMutation("held-work_a") { token ->
                        order.add("registered")
                        entered.countDown()
                        check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                        val producer = policy.saveAcknowledgement(token, edit)
                        ack.set(producer)
                        order.add("submitted")
                        task.task.addOnCompleteListener(java.util.concurrent.Executor { it.run() }) { result ->
                            producer.backendSettled(result.isSuccessful)
                        }
                        order.add("attached")
                    }
                } catch (failure: Throwable) { error.set(failure) }
            }, "host-held-sdk-submission").also { it.start() }
            check(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
            reader = Thread({
                try {
                    drainAttempt.countDown()
                    drain.set(policy.beginDrain())
                    order.add("drained")
                } catch (failure: Throwable) { error.set(failure) }
            }, "host-concurrent-drain").also { it.start() }
            check(drainAttempt.await(10, java.util.concurrent.TimeUnit.SECONDS))
            // Prove actual monitor ownership, rather than treating a short timeout as a causal oracle.
            val threads = java.lang.management.ManagementFactory.getThreadMXBean()
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
            var blockedBySubmitter = false
            while (!blockedBySubmitter && System.nanoTime() < deadline && reader!!.isAlive) {
                val info = threads.getThreadInfo(reader!!.id)
                blockedBySubmitter = info?.threadState == Thread.State.BLOCKED && info.lockOwnerId == submitter!!.id
                if (!blockedBySubmitter) Thread.yield()
            }
            org.junit.Assert.assertTrue(blockedBySubmitter)
            assertEquals(listOf("registered"), order.toList())
            release.countDown()
            submitter!!.join(10_000)
            reader!!.join(10_000)
            assertFalse(submitter!!.isAlive)
            assertFalse(reader!!.isAlive)
            assertEquals(null, error.get())
            assertEquals(listOf("registered", "submitted", "attached", "drained"), order.toList())
            org.junit.Assert.assertNotNull(drain.get())
            // Listener was attached before completion; rejection signals once and performs no fake publication.
            task.setException(IllegalStateException("backend rejected"))
            assertEquals(ReviewRemoteResult.FAILED, kotlinx.coroutines.withTimeout(10_000) { ack.get().receipt.awaitRemote() })
            val fresh = ConfirmedReviewSnapshots(namespace, HostFileStorage(root))
            val truth = fresh.project(fresh.beginRead("held-work"), "a", ReviewSnapshotFrame(emptyList(), true, false)) as ReviewReadResult.Snapshot
            assertEquals(listOf(seed), truth.confirmed)
            assertEquals(emptyList<ListenerReview>(), truth.pending)
            assertFalse(truth.readFailed)
        } finally {
            release.countDown()
            submitter?.join(10_000)
            reader?.join(10_000)
            check(submitter?.isAlive != true && reader?.isAlive != true)
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun newSubmissionInvalidatesDrainAndAlreadyEligibleServerToken() = runBlocking {
        for (phase in listOf("after-drain", "after-server-token")) {
            val root = Files.createTempDirectory("confirmed-drain-version-")
            try {
                val namespace = ReviewSnapshotNamespace("demo-slukhayka-acceptance", "host-version-$phase", "com.slukhayka.audiobooks")
                val seed = ListenerReview("version-work", "a", "Читач A", 3, "Початковий", "Видання A", 100L, null)
                val policy = ConfirmedReviewSnapshots(namespace, HostFileStorage(root))
                val seeded = policy.project(policy.beginRead("version-work"), "a", ReviewSnapshotFrame(listOf(
                    ReviewSnapshotDocument("version-work_a", seed, false)), false, false)) as ReviewReadResult.Snapshot
                assertEquals(listOf(seed), seeded.confirmed)
                val drain = policy.beginDrain()
                val server = if (phase == "after-server-token") requireNotNull(policy.beginServerRead("version-work", drain)) else null
                policy.enqueueMutation("version-work_a") { /* Controlled synchronous submission boundary; no SDK queue claim. */ }
                if (server == null) assertEquals(null, policy.beginServerRead("version-work", drain))
                else assertEquals(ReviewReadResult.Failure, policy.project(server, "a", ReviewSnapshotFrame(
                    emptyList(), false, false, ReviewSnapshotOrigin.POST_DRAIN_SERVER)))
                val fresh = ConfirmedReviewSnapshots(namespace, HostFileStorage(root))
                val truth = fresh.project(fresh.beginRead("version-work"), "a", ReviewSnapshotFrame(emptyList(), true, false)) as ReviewReadResult.Snapshot
                assertEquals(listOf(seed), truth.confirmed)
                assertEquals(emptyList<ListenerReview>(), truth.pending)
                assertFalse(truth.readFailed)
            } finally { root.toFile().deleteRecursively() }
        }
    }

    @Test
    fun submissionThrowRejectedAndCancelledTasksReleaseCoordinationWithZeroCacheIo() = runBlocking {
        for (failure in listOf("submission-throw", "rejected", "cancelled")) {
            val root = Files.createTempDirectory("confirmed-submission-failure-")
            try {
                val namespace = ReviewSnapshotNamespace("demo-slukhayka-acceptance", "host-$failure", "com.slukhayka.audiobooks")
                val seed = ListenerReview("failure-work", "a", "Читач A", 3, "Початковий", "Видання A", 100L, null)
                val edit = ListenerReview("failure-work", "a", "Читач A", 5, "Відхилений", "Видання A", 100L, 200L)
                val bytes = HostFileStorage(root)
                val first = ConfirmedReviewSnapshots(namespace, bytes)
                val seeded = first.project(first.beginRead("failure-work"), "a", ReviewSnapshotFrame(listOf(
                    ReviewSnapshotDocument("failure-work_a", seed, false)), false, false)) as ReviewReadResult.Snapshot
                assertEquals(listOf(seed), seeded.confirmed)
                val io = java.util.concurrent.atomic.AtomicInteger()
                val counted = object : ReviewSnapshotStorage by bytes {
                    override fun read(key: String): ReviewSnapshotBytes { io.incrementAndGet(); return bytes.read(key) }
                    override fun write(key: String, content: ByteArray): Boolean { io.incrementAndGet(); return bytes.write(key, content) }
                    override fun keys(): List<String>? { io.incrementAndGet(); return bytes.keys() }
                }
                val policy = ConfirmedReviewSnapshots(namespace, counted)
                val previousDrain = policy.beginDrain()
                if (failure == "submission-throw") {
                    var rejected = false
                    try { policy.enqueueMutation("failure-work_a") { throw IllegalStateException("SDK submission failed") } }
                    catch (_: IllegalStateException) { rejected = true }
                    org.junit.Assert.assertTrue(rejected)
                } else {
                    val cancellation = DirectHostCancellationToken()
                    val task = com.google.android.gms.tasks.TaskCompletionSource<Unit>(cancellation)
                    val ack = policy.enqueueMutation("failure-work_a") { token ->
                        val producer = policy.saveAcknowledgement(token, edit)
                        task.task.addOnCompleteListener(java.util.concurrent.Executor { it.run() }) { completed ->
                            producer.backendSettled(completed.isSuccessful)
                        }
                        producer
                    }
                    if (failure == "cancelled") cancellation.cancel() else task.setException(IllegalStateException("backend rejected"))
                    assertEquals(ReviewRemoteResult.FAILED, kotlinx.coroutines.withTimeout(10_000) { ack.receipt.awaitRemote() })
                }
                assertEquals(0, io.get())
                assertEquals(null, policy.beginServerRead("failure-work", previousDrain))
                // Exception/cancellation cannot strand coordination; no cache read/write is needed to capture a fresh token.
                org.junit.Assert.assertNotNull(policy.beginServerRead("failure-work", policy.beginDrain()))
                assertEquals(0, io.get())
                val fresh = ConfirmedReviewSnapshots(namespace, HostFileStorage(root))
                val truth = fresh.project(fresh.beginRead("failure-work"), "a", ReviewSnapshotFrame(emptyList(), true, false)) as ReviewReadResult.Snapshot
                assertEquals(listOf(seed), truth.confirmed)
                assertEquals(emptyList<ListenerReview>(), truth.pending)
                assertFalse(truth.readFailed)
            } finally { root.toFile().deleteRecursively() }
        }
    }

    @Test
    fun genuinelyEligibleEmptyReplacesAndPersistsCompleteAbsence() = runBlocking {
        val root = Files.createTempDirectory("confirmed-eligible-empty-")
        try {
            val namespace = ReviewSnapshotNamespace("demo-slukhayka-acceptance", "host-eligible-empty", "com.slukhayka.audiobooks")
            val seed = ListenerReview("eligible-work", "a", "Читач A", 3, "Початковий", "Видання A", 100L, null)
            val policy = ConfirmedReviewSnapshots(namespace, HostFileStorage(root))
            val seeded = policy.project(policy.beginRead("eligible-work"), "a", ReviewSnapshotFrame(listOf(
                ReviewSnapshotDocument("eligible-work_a", seed, false)), false, false)) as ReviewReadResult.Snapshot
            assertEquals(listOf(seed), seeded.confirmed)
            val drain = policy.beginDrain()
            // Pure public policy models successful drain completion; real SDK Task/device proof is separate.
            val token = requireNotNull(policy.beginServerRead("eligible-work", drain))
            val empty = policy.project(token, "a", ReviewSnapshotFrame(emptyList(), false, false,
                ReviewSnapshotOrigin.POST_DRAIN_SERVER)) as ReviewReadResult.Snapshot
            assertEquals(emptyList<ListenerReview>(), empty.confirmed)
            assertEquals(emptyList<ListenerReview>(), empty.pending)
            org.junit.Assert.assertTrue(empty.authoritative)
            assertFalse(empty.readFailed)
            val fresh = ConfirmedReviewSnapshots(namespace, HostFileStorage(root))
            val restored = fresh.project(fresh.beginRead("eligible-work"), "a", ReviewSnapshotFrame(emptyList(), true, false)) as ReviewReadResult.Snapshot
            assertEquals(emptyList<ListenerReview>(), restored.confirmed)
            assertEquals(emptyList<ListenerReview>(), restored.pending)
            assertFalse(restored.authoritative)
            assertFalse(restored.readFailed)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun foreignNamespaceAndStorageDrainCannotAuthorizeAnotherCoordinator() = runBlocking {
        val root = Files.createTempDirectory("confirmed-foreign-drain-")
        val otherRoot = Files.createTempDirectory("confirmed-foreign-storage-")
        try {
            val namespace = ReviewSnapshotNamespace("demo-slukhayka-acceptance", "host-foreign-drain", "com.slukhayka.audiobooks")
            val foreignNamespace = ReviewSnapshotNamespace("demo-slukhayka-acceptance", "another-app", "com.slukhayka.audiobooks")
            val seed = ListenerReview("foreign-work", "a", "Читач A", 3, "Початковий", "Видання A", 100L, null)
            val first = ConfirmedReviewSnapshots(namespace, HostFileStorage(root))
            val seeded = first.project(first.beginRead("foreign-work"), "a", ReviewSnapshotFrame(listOf(
                ReviewSnapshotDocument("foreign-work_a", seed, false)), false, false)) as ReviewReadResult.Snapshot
            assertEquals(listOf(seed), seeded.confirmed)
            val drain = first.beginDrain()
            val token = requireNotNull(first.beginServerRead("foreign-work", drain))
            for (other in listOf(ConfirmedReviewSnapshots(foreignNamespace, HostFileStorage(root)),
                ConfirmedReviewSnapshots(namespace, HostFileStorage(otherRoot)))) {
                assertEquals(null, other.beginServerRead("foreign-work", drain))
                assertEquals(ReviewReadResult.Failure, other.project(token, "a", ReviewSnapshotFrame(
                    emptyList(), false, false, ReviewSnapshotOrigin.POST_DRAIN_SERVER)))
            }
            val fresh = ConfirmedReviewSnapshots(namespace, HostFileStorage(root))
            val truth = fresh.project(fresh.beginRead("foreign-work"), "a", ReviewSnapshotFrame(emptyList(), true, false)) as ReviewReadResult.Snapshot
            assertEquals(listOf(seed), truth.confirmed)
            assertEquals(emptyList<ListenerReview>(), truth.pending)
            assertFalse(truth.readFailed)
        } finally { root.toFile().deleteRecursively(); otherRoot.toFile().deleteRecursively() }
    }

    /** Public CancellationToken supplies direct host callbacks; actual TaskCompletionSource owns Task cancellation. */
    private class DirectHostCancellationToken : com.google.android.gms.tasks.CancellationToken() {
        private val lock = Any()
        private var requested = false
        private val listeners = mutableListOf<com.google.android.gms.tasks.OnTokenCanceledListener>()
        override fun isCancellationRequested(): Boolean = synchronized(lock) { requested }
        override fun onCanceledRequested(listener: com.google.android.gms.tasks.OnTokenCanceledListener): com.google.android.gms.tasks.CancellationToken {
            val immediately = synchronized(lock) {
                if (requested) true else { listeners.add(listener); false }
            }
            if (immediately) listener.onCanceled()
            return this
        }
        fun cancel() {
            val callbacks = synchronized(lock) {
                if (requested) return
                requested = true
                listeners.toList().also { listeners.clear() }
            }
            callbacks.forEach { it.onCanceled() }
        }
    }

    // PRIVATE exact REST review regressions, NOT APPLIED / COMPILED / RUN.
    @Test
    fun uncertainFailureReadStopsNamespaceScanAfterOneUnknownPayload() = runBlocking {
        val root = Files.createTempDirectory("confirmed-uncertain-read-budget-")
        try {
            val namespace = ReviewSnapshotNamespace("demo-slukhayka-acceptance", "host-uncertain-read", "com.slukhayka.audiobooks")
            val seed = ListenerReview("uncertain-work", "a", "Читач A", 3, "Початковий", "Видання A", 100L, null)
            val bytes = HostFileStorage(root)
            val first = ConfirmedReviewSnapshots(namespace, bytes)
            val seeded = first.project(first.beginRead("uncertain-work"), "a", ReviewSnapshotFrame(listOf(
                ReviewSnapshotDocument("uncertain-work_a", seed, false)), false, false)) as ReviewReadResult.Snapshot
            assertEquals(listOf(seed), seeded.confirmed)
            val realKey = bytes.keys()!!.single()
            val keys = (listOf(realKey) + (0 until 255).map { "%064x".format(it) }).distinct()
            assertEquals(256, keys.size)
            val reads = java.util.concurrent.atomic.AtomicInteger()
            val uncertain = java.util.concurrent.atomic.AtomicInteger()
            val writes = java.util.concurrent.atomic.AtomicInteger()
            val incomplete = object : ReviewSnapshotStorage by bytes {
                override fun keys(): List<String> = keys
                override fun read(key: String): ReviewSnapshotBytes {
                    reads.incrementAndGet()
                    if (key == realKey) return bytes.read(key)
                    uncertain.incrementAndGet()
                    // Models the outcome of an already consumed bounded failure; actual Atomic overshoot is separate Android proof.
                    return ReviewSnapshotBytes.Failure
                }
                override fun write(key: String, content: ByteArray): Boolean { writes.incrementAndGet(); return bytes.write(key, content) }
            }
            val policy = ConfirmedReviewSnapshots(namespace, incomplete)
            val ack = policy.deleteAcknowledgement(policy.beginMutation("uncertain-work_a"))
            ack.backendSettled(true)
            assertEquals(true, kotlinx.coroutines.withTimeout(10_000) { ack.receipt.awaitRemote() })
            assertEquals(2, reads.get())
            assertEquals(1, uncertain.get())
            assertEquals(0, writes.get())
            val fresh = ConfirmedReviewSnapshots(namespace, HostFileStorage(root))
            val truth = fresh.project(fresh.beginRead("uncertain-work"), "a", ReviewSnapshotFrame(emptyList(), true, false)) as ReviewReadResult.Snapshot
            assertEquals(listOf(seed), truth.confirmed)
            assertEquals(emptyList<ListenerReview>(), truth.pending)
            org.junit.Assert.assertTrue(truth.readFailed)
            assertFalse(truth.authoritative)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun repeatedAcceptedDeleteCannotTreatMissingHistoricalFailedKeyAsRepair() = runBlocking {
        val root = Files.createTempDirectory("confirmed-repeated-delete-history-")
        try {
            val namespace = ReviewSnapshotNamespace("demo-slukhayka-acceptance", "host-delete-history", "com.slukhayka.audiobooks")
            val seedA = ListenerReview("history-a", "a", "Читач A", 3, "Початковий A", "Видання A", 100L, null)
            val seedB = ListenerReview("history-b", "b", "Читач B", 4, "Початковий B", "Видання B", 90L, null)
            val bytes = HostFileStorage(root)
            val policy = ConfirmedReviewSnapshots(namespace, bytes)
            val a = policy.project(policy.beginRead("history-a"), "a", ReviewSnapshotFrame(listOf(
                ReviewSnapshotDocument("history-a_a", seedA, false)), false, false)) as ReviewReadResult.Snapshot
            assertEquals(listOf(seedA), a.confirmed)
            val aKeys = bytes.keys()!!.toSet()
            val b = policy.project(policy.beginRead("history-b"), "b", ReviewSnapshotFrame(listOf(
                ReviewSnapshotDocument("history-b_b", seedB, false)), false, false)) as ReviewReadResult.Snapshot
            assertEquals(listOf(seedB), b.confirmed)
            // Public storage key-difference targets corruption; returned DTO expectations stay independent literals.
            val badKey = (bytes.keys()!!.toSet() - aKeys).single()
            org.junit.Assert.assertTrue(bytes.write(badKey, byteArrayOf(1, 2, 3)))
            val first = policy.deleteAcknowledgement(policy.beginMutation("history-a_a"))
            first.backendSettled(true)
            assertEquals(true, kotlinx.coroutines.withTimeout(10_000) { first.receipt.awaitRemote() })
            val afterFirst = policy.project(policy.beginRead("history-a"), "a", ReviewSnapshotFrame(emptyList(), true, false)) as ReviewReadResult.Snapshot
            assertEquals(listOf(seedA), afterFirst.confirmed)
            org.junit.Assert.assertTrue(afterFirst.readFailed)
            Files.delete(root.resolve("$badKey.bin"))
            val second = policy.deleteAcknowledgement(policy.beginMutation("history-a_a"))
            second.backendSettled(true)
            assertEquals(true, kotlinx.coroutines.withTimeout(10_000) { second.receipt.awaitRemote() })
            val afterSecond = ConfirmedReviewSnapshots(namespace, HostFileStorage(root))
            val stillDegraded = afterSecond.project(afterSecond.beginRead("history-a"), "a", ReviewSnapshotFrame(emptyList(), true, false)) as ReviewReadResult.Snapshot
            assertEquals(emptyList<ListenerReview>(), stillDegraded.confirmed)
            assertEquals(emptyList<ListenerReview>(), stillDegraded.pending)
            org.junit.Assert.assertTrue(stillDegraded.readFailed)
            // Only actual eligible whole truth recreating the failed key, with complete validation, can repair history.
            val repaired = afterSecond.project(requireNotNull(afterSecond.beginServerRead("history-b", afterSecond.beginDrain())), "b", ReviewSnapshotFrame(listOf(
                ReviewSnapshotDocument("history-b_b", seedB, false)), false, false, ReviewSnapshotOrigin.POST_DRAIN_SERVER)) as ReviewReadResult.Snapshot
            assertEquals(listOf(seedB), repaired.confirmed)
            org.junit.Assert.assertTrue(repaired.authoritative)
            assertFalse(repaired.readFailed)
            val fresh = ConfirmedReviewSnapshots(namespace, HostFileStorage(root))
            val finalA = fresh.project(fresh.beginRead("history-a"), "a", ReviewSnapshotFrame(emptyList(), true, false)) as ReviewReadResult.Snapshot
            assertEquals(emptyList<ListenerReview>(), finalA.confirmed)
            assertFalse(finalA.readFailed)
            val finalB = fresh.project(fresh.beginRead("history-b"), "b", ReviewSnapshotFrame(emptyList(), true, false)) as ReviewReadResult.Snapshot
            assertEquals(listOf(seedB), finalB.confirmed)
            assertFalse(finalB.readFailed)
        } finally { root.toFile().deleteRecursively() }
    }

    private class HeldAcknowledgementDispatcher : kotlinx.coroutines.CoroutineDispatcher() {
        private val queued = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { queued.add(block) }
        fun runQueued() { while (true) (queued.poll() ?: return).run() }
    }

    // Host byte-storage implementation for a public policy seam. It is not production Android AtomicFile.
    private class HostFileStorage(private val root: Path) : ReviewSnapshotStorage {
        override val scopeId = root.toFile().canonicalPath
        private fun path(key: String): Path {
            require(key.matches(Regex("[a-f0-9]{64}")))
            return root.resolve("$key.bin")
        }
        override fun read(key: String): ReviewSnapshotBytes = try {
            val file = path(key)
            if (!Files.exists(file)) ReviewSnapshotBytes.Missing else ReviewSnapshotBytes.Data(Files.readAllBytes(file))
        } catch (_: Exception) { ReviewSnapshotBytes.Failure }
        override fun write(key: String, bytes: ByteArray): Boolean = try {
            val temp = Files.createTempFile(root, "confirmed-write-", ".tmp")
            try {
                Files.write(temp, bytes)
                Files.move(temp, path(key), ATOMIC_MOVE, REPLACE_EXISTING)
                true
            } finally { Files.deleteIfExists(temp) }
        } catch (_: Exception) { false }
        override fun keys(): List<String>? = try {
            Files.list(root).use { stream ->
                stream.map { it.fileName.toString() }
                    .filter { it.matches(Regex("[a-f0-9]{64}\\.bin")) }
                    .map { it.removeSuffix(".bin") }.toList()
            }
        } catch (_: Exception) { null }
    }
}
