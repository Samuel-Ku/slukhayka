package com.slukhayka.audiobooks.data.source

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Spec #681 T1 (#682) — the persistent carrier: a budget saved by one store
 * instance (one process) is read back by the next, so politeness never
 * resets with a restart. Hosts never share a bucket.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SharedPreferencesSourceGateBudgetStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `a saved budget survives a new store instance`() {
        val state = SourceBucketState(tokens = 2, lastRefillAtMs = 1_700_000_000_000L)
        SharedPreferencesSourceGateBudgetStore(context).save("4read.org", state)

        val restarted = SharedPreferencesSourceGateBudgetStore(context)
        assertEquals(state, restarted.load("4read.org"))
    }

    @Test
    fun `an unknown host has no budget`() {
        assertNull(SharedPreferencesSourceGateBudgetStore(context).load("unknown.example"))
    }

    @Test
    fun `hosts keep separate budgets`() {
        val store = SharedPreferencesSourceGateBudgetStore(context)
        store.save("a.example", SourceBucketState(tokens = 1, lastRefillAtMs = 100L))
        store.save("b.example", SourceBucketState(tokens = 5, lastRefillAtMs = 200L))

        assertEquals(SourceBucketState(1, 100L), store.load("a.example"))
        assertEquals(SourceBucketState(5, 200L), store.load("b.example"))
    }
}
