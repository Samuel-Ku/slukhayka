package com.slukhayka.audiobooks.data.availability

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ADR-0042 §1 (spec-56 T1) — the persisted availability verdict store: empty
 * by default, the last verdict survives a store re-creation (the card must
 * keep its state after a restart), a fresh verdict replaces the previous one,
 * and a blank mergeKey is never a state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LibraryAvailabilityStoreTest {

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `default is no verdict`() {
        val store = LibraryAvailabilityStore(context())
        assertNull(store.verdictFor("key"))
    }

    @Test
    fun `a verdict survives a store re-creation`() {
        LibraryAvailabilityStore(context()).record(
            mergeKey = "key",
            status = AvailabilityStatus.NOT_FOUND,
            observedAtMs = 1_700_000_000_000L
        )

        val reloaded = LibraryAvailabilityStore(context()).verdictFor("key")
        assertEquals(AvailabilityStatus.NOT_FOUND, reloaded?.status)
        assertEquals(1_700_000_000_000L, reloaded?.observedAtMs)
    }

    @Test
    fun `recording replaces the previous verdict`() {
        val store = LibraryAvailabilityStore(context())
        store.record("key", AvailabilityStatus.NOT_FOUND, observedAtMs = 1L)
        store.record("key", AvailabilityStatus.FOUND, sourceId = "sluhayua", observedAtMs = 2L)

        val verdict = store.verdictFor("key")
        assertEquals(AvailabilityStatus.FOUND, verdict?.status)
        assertEquals("sluhayua", verdict?.sourceId)
        assertEquals(2L, verdict?.observedAtMs)
    }

    @Test
    fun `a blank mergeKey is never a state`() {
        val store = LibraryAvailabilityStore(context())
        store.record("", AvailabilityStatus.NOT_FOUND, observedAtMs = 1L)
        assertNull(store.verdictFor(""))
    }
}
