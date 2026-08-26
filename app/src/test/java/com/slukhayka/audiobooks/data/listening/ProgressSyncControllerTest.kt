package com.slukhayka.audiobooks.data.listening

import com.slukhayka.audiobooks.data.db.PlaybackProgressEntity
import com.slukhayka.audiobooks.data.identity.FakeListenerIdentity
import com.slukhayka.audiobooks.data.identity.ListenerIdentity
import com.slukhayka.audiobooks.data.identity.ListenerProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-0023 (spec-43 T6) — the controller over pure fakes: pull-before-resume,
 * throttled push-after-save, the local-profile guard and the toggle. No
 * Android, no Firebase, no Room (prior art: the identity seam's JVM fakes).
 */
class ProgressSyncControllerTest {

    private class FakeMirror : ProgressMirror {
        val rows = mutableMapOf<String, PlaybackProgressEntity>()
        private val editionToBook = mutableMapOf<String, String>()
        var appliedCount = 0

        fun seed(bookId: String, editionId: String, entity: PlaybackProgressEntity) {
            editionToBook[editionId] = bookId
            rows[editionId] = entity
        }

        override suspend fun editionIdForSync(bookId: String): String =
            editionToBook.entries.firstOrNull { it.value == bookId }?.key
                ?: error("no edition seeded for $bookId")

        override suspend fun progressByEdition(editionId: String): PlaybackProgressEntity? =
            rows[editionId]

        override suspend fun applyRemoteProgress(
            bookId: String,
            state: RemoteListeningState,
            nowMs: Long
        ) {
            appliedCount++
            rows[state.editionId] = PlaybackProgressEntity(
                editionId = state.editionId,
                bookId = bookId,
                currentChapterIndex = state.chapterIndex,
                currentPositionSeconds = state.positionSeconds,
                lastListenedAt = nowMs,
                isCompleted = state.isCompleted,
                lastPausedAtEpochMs = null,
                preferredSpeed = state.preferredSpeed
            )
        }
    }

    private class FakeLedger : ProgressSyncLedger {
        val synced = mutableMapOf<String, Long>()
        val attempts = mutableMapOf<String, Long>()
        override fun lastSyncedServerMs(editionId: String): Long? = synced[editionId]
        override fun recordSyncedServerMs(editionId: String, serverMs: Long) {
            synced[editionId] = serverMs
        }
        override fun lastPushAttemptMs(editionId: String): Long? = attempts[editionId]
        override fun recordPushAttempt(editionId: String, atMs: Long) {
            attempts[editionId] = atMs
        }
    }

    private class FakeStore : ListenerProgressSyncStore {
        val pushed = mutableListOf<RemoteListeningState>()
        var cloud: Map<String, Map<String, Any>> = emptyMap()
        var pushStamp: Long? = 9000L

        override suspend fun fetchDocument(documentId: String): Map<String, Any>? = cloud[documentId]
        override suspend fun writeDocument(documentId: String, fields: Map<String, Any>): Boolean {
            pushed += ProgressSyncCodec.fromDocument(
                fields + mapOf(ProgressSyncCodec.FIELD_UPDATED_AT to (pushStamp ?: 0L))
            )!!
            return true
        }
        override suspend fun readServerUpdatedAtMs(documentId: String): Long? = pushStamp
    }

    /** The offline bootstrap profile: uid starts with `local-`, nothing may upload. */
    private class LocalOnlyFake : ListenerIdentity {
        override suspend fun ensure() = ListenerProfile("local-offline", "Слухач")
        override suspend fun current() = null
        override suspend fun setNickname(nickname: String) {}
        override suspend fun recoveryCode(): String? = null
        override suspend fun restoreFromCode(code: String): ListenerProfile? = null
    }

    private fun seededMirror(): Pair<FakeMirror, String> {
        val mirror = FakeMirror()
        mirror.seed(
            bookId = "book-1",
            editionId = "ed-1",
            entity = PlaybackProgressEntity(
                editionId = "ed-1",
                bookId = "book-1",
                currentChapterIndex = 0,
                currentPositionSeconds = 10L
            )
        )
        return mirror to "book-1"
    }

    /** The document key the controller will really use for this fake identity. */
    private fun keyFor(identity: ListenerIdentity, editionId: String = "ed-1"): String =
        runBlocking { ProgressSyncPolicy.documentId(identity.ensure().uid, editionId) }

    @Test
    fun `a newer cloud state lands before resume and becomes the sync point`() = runBlocking {
        val (mirror, bookId) = seededMirror()
        val ledger = FakeLedger()
        val identity = FakeListenerIdentity(kotlin.random.Random(1))
        val store = FakeStore().apply {
            cloud = mapOf(
                keyFor(identity) to mapOf(
                    ProgressSyncCodec.FIELD_EDITION_ID to "ed-1",
                    ProgressSyncCodec.FIELD_CHAPTER_INDEX to 4L,
                    ProgressSyncCodec.FIELD_POSITION_SECONDS to 500L,
                    ProgressSyncCodec.FIELD_IS_COMPLETED to false,
                    ProgressSyncCodec.FIELD_PREFERRED_SPEED to 1.5f,
                    ProgressSyncCodec.FIELD_UPDATED_AT to 7000L
                )
            )
        }
        val controller = ProgressSyncController(
            identity = identity,
            mirror = mirror,
            store = store,
            ledger = ledger
        )

        controller.pullBeforeResume(bookId)

        val row = mirror.rows["ed-1"]
        assertNotNull(row)
        assertEquals(4, row!!.currentChapterIndex)
        assertEquals(500L, row.currentPositionSeconds)
        assertEquals(1.5f, row.preferredSpeed)
        assertNull(row.lastPausedAtEpochMs) // Smart Rewind stays THIS device's business
        assertEquals(7000L, ledger.synced["ed-1"])
    }

    @Test
    fun `an already-seen cloud state never rewrites the local row`() = runBlocking {
        val (mirror, bookId) = seededMirror()
        val ledger = FakeLedger().apply { synced["ed-1"] = 7000L }
        val identity = FakeListenerIdentity(kotlin.random.Random(1))
        val store = FakeStore().apply {
            cloud = mapOf(
                keyFor(identity) to mapOf(
                    ProgressSyncCodec.FIELD_EDITION_ID to "ed-1",
                    ProgressSyncCodec.FIELD_CHAPTER_INDEX to 4L,
                    ProgressSyncCodec.FIELD_POSITION_SECONDS to 500L,
                    ProgressSyncCodec.FIELD_IS_COMPLETED to false,
                    ProgressSyncCodec.FIELD_UPDATED_AT to 7000L
                )
            )
        }
        val controller = ProgressSyncController(
            identity = identity,
            mirror = mirror,
            store = store,
            ledger = ledger
        )

        controller.pullBeforeResume(bookId)

        assertEquals(0, mirror.appliedCount)
        assertEquals(10L, mirror.rows["ed-1"]!!.currentPositionSeconds)
    }

    @Test
    fun `honest moments push at once, periodic ticks ride the pacing window`() = runBlocking {
        var clock = 100_000L
        val (mirror, bookId) = seededMirror()
        val ledger = FakeLedger()
        val store = FakeStore()
        val controller = ProgressSyncController(
            identity = FakeListenerIdentity(kotlin.random.Random(1)),
            mirror = mirror,
            store = store,
            ledger = ledger,
            nowMs = { clock }
        )

        controller.pushAfterSave(bookId, immediate = true)
        assertEquals(1, store.pushed.size)
        assertEquals(10L, store.pushed.first().positionSeconds) // the seeded local truth

        // A periodic tick right after the pause: inside the window, refused.
        clock += 5_000L
        controller.pushAfterSave(bookId, immediate = false)
        assertEquals(1, store.pushed.size)

        // Past the window: goes.
        clock += ProgressSyncPolicy.MIN_PUSH_INTERVAL_MS
        controller.pushAfterSave(bookId, immediate = false)
        assertEquals(2, store.pushed.size)
        // And its accepted server stamp became the new sync point.
        assertEquals(9000L, ledger.synced["ed-1"])
    }

    @Test
    fun `the switch off means nothing reaches the wire`() = runBlocking {
        val (mirror, bookId) = seededMirror()
        val store = FakeStore()
        val controller = ProgressSyncController(
            identity = FakeListenerIdentity(kotlin.random.Random(1)),
            mirror = mirror,
            store = store,
            ledger = FakeLedger(),
            isEnabled = { false }
        )

        controller.pullBeforeResume(bookId)
        controller.pushAfterSave(bookId, immediate = true)

        assertTrue(store.pushed.isEmpty())
        assertTrue(store.cloud.isEmpty())
    }

    @Test
    fun `a local-only bootstrap profile never uploads`() = runBlocking {
        val (mirror, bookId) = seededMirror()
        val store = FakeStore()
        val controller = ProgressSyncController(
            identity = LocalOnlyFake(),
            mirror = mirror,
            store = store,
            ledger = FakeLedger()
        )

        controller.pullBeforeResume(bookId)
        controller.pushAfterSave(bookId, immediate = true)

        assertFalse(store.pushed.isNotEmpty())
    }

    @Test
    fun `no firebase store means both paths are no-ops`() = runBlocking {
        val (mirror, bookId) = seededMirror()
        val controller = ProgressSyncController(
            identity = FakeListenerIdentity(kotlin.random.Random(1)),
            mirror = mirror,
            store = null,
            ledger = FakeLedger()
        )

        controller.pullBeforeResume(bookId)
        controller.pushAfterSave(bookId, immediate = true)

        assertEquals(0, mirror.appliedCount)
    }
}
