package com.slukhayka.audiobooks.data.source

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
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

    /**
     * #1730 — App.onCreate builds the source gate with this store before any
     * network module exists. getSharedPreferences is a disk-backed read, so
     * the constructor must not perform it; the first load/save — on the gate's
     * IO path — may.
     */
    @Test
    fun `construction opens no preferences and the first load still works`() {
        var opens = 0
        val countingContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this

            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                opens++
                return super.getSharedPreferences(name, mode)
            }
        }

        val store = SharedPreferencesSourceGateBudgetStore(countingContext)
        assertEquals("construction runs on main before any network", 0, opens)

        assertNull(store.load("first-use.example"))
        assertEquals("the first load creates the file exactly once", 1, opens)

        val saved = SourceBucketState(tokens = 2, lastRefillAtMs = 1_700_000_000_000L)
        store.save("first-use.example", saved)
        assertEquals(saved, SharedPreferencesSourceGateBudgetStore(countingContext).load("first-use.example"))
    }
}
