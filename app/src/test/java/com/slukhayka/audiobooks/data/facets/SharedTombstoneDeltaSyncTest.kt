package com.slukhayka.audiobooks.data.facets

import com.slukhayka.audiobooks.data.metadata.SharedTombstone
import com.slukhayka.audiobooks.data.metadata.TombstoneTargetKind
import com.slukhayka.audiobooks.testing.FakeSharedBookMetaStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-0035 / #607 — the shared-tombstone consumption lane (the
 * FacetDeltaSync/SubmissionDeltaSync precedent): one bounded chain pulls
 * curator blocks into the local tombstone machinery, the cursor advances
 * only past committed pages, a failing store contributes nothing, and the
 * lane never throws.
 */
class SharedTombstoneDeltaSyncTest {

    private class RecordingWriter : TombstoneProjectionWriter {
        val applied = mutableListOf<SharedTombstone>()

        override suspend fun apply(tombstones: List<SharedTombstone>) {
            applied += tombstones
        }
    }

    private val store = FakeSharedBookMetaStore()
    private val writer = RecordingWriter()
    private val cursorStore = InMemorySharedTombstoneSyncCursorStore()
    private val sync = SharedTombstoneDeltaSync(store, writer, cursorStore)

    private fun tombstone(mergeKey: String, placedAt: Long) = SharedTombstone(
        targetKind = TombstoneTargetKind.WORK,
        mergeKey = mergeKey,
        placedAt = placedAt,
        curatorId = "curator-7"
    )

    @Test
    fun `one chain applies every tombstone and advances the cursor`() = runBlocking {
        store.putSharedTombstone(tombstone("острів дума|стівен кінг", placedAt = 100))
        store.putSharedTombstone(tombstone("темна вежа 1|стівен кінг", placedAt = 200))

        val result = sync.syncAvailablePages()

        assertEquals(SharedTombstoneDeltaSync.ChainResult(pagesApplied = 1, tombstonesApplied = 2), result)
        assertEquals(2, writer.applied.size)
        val cursor = cursorStore.load()
        assertTrue("the cursor advanced past the last committed tombstone", cursor != null && cursor.placedAt == 200L)
    }

    @Test
    fun `two pages chain to the end`() = runBlocking {
        store.putSharedTombstone(tombstone("острів дума|стівен кінг", placedAt = 100))
        store.putSharedTombstone(tombstone("темна вежа 1|стівен кінг", placedAt = 200))

        val result = sync.syncAvailablePages(pageSize = 1)

        assertEquals(SharedTombstoneDeltaSync.ChainResult(pagesApplied = 2, tombstonesApplied = 2), result)
        assertEquals(2, writer.applied.size)
    }

    @Test
    fun `empty store is NoChanges and the cursor stays put`() = runBlocking {
        assertEquals(SharedTombstoneDeltaSync.PageResult.NoChanges, sync.syncPage())
        assertNull(cursorStore.load())
    }

    @Test
    fun `a failing store contributes nothing and never throws`() = runBlocking {
        val failingStore = FakeSharedBookMetaStore(throwOnTombstonePage = true)
        val lane = SharedTombstoneDeltaSync(failingStore, writer, InMemorySharedTombstoneSyncCursorStore())
        assertEquals(SharedTombstoneDeltaSync.PageResult.Failed, lane.syncPage())
        assertEquals(SharedTombstoneDeltaSync.ChainResult(0, 0), lane.syncAvailablePages())
        assertTrue(writer.applied.isEmpty())
    }

    @Test
    fun `a failing writer does not advance the cursor`() = runBlocking {
        store.putSharedTombstone(tombstone("острів дума|стівен кінг", placedAt = 100))
        val broken = object : TombstoneProjectionWriter {
            override suspend fun apply(tombstones: List<SharedTombstone>) {
                throw IllegalStateException("projection full")
            }
        }
        val lane = SharedTombstoneDeltaSync(store, broken, cursorStore)
        assertEquals(SharedTombstoneDeltaSync.PageResult.Failed, lane.syncPage())
        assertNull(cursorStore.load())
    }
}