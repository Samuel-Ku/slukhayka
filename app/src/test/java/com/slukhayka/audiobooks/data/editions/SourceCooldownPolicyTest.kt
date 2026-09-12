package com.slukhayka.audiobooks.data.editions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #530 — a failed Source parks in a BOUNDED window: it is skipped as a
 * candidate (no fallback loop), never deleted, and eligible again once the
 * window passes; a success clears it immediately.
 */
class SourceCooldownPolicyTest {

    private fun store(): Pair<SourceCooldownStore, File> {
        val file = File.createTempFile("source-cooldown", ".tsv").apply { delete() }
        return SourceCooldownStore(file) to file
    }

    @Test
    fun `the cooldown doubles from a minute and stays bounded`() {
        assertEquals(0L, SourceCooldownPolicy.cooldownFor(0))
        assertEquals(60_000L, SourceCooldownPolicy.cooldownFor(1))
        assertEquals(120_000L, SourceCooldownPolicy.cooldownFor(2))
        assertEquals(240_000L, SourceCooldownPolicy.cooldownFor(3))
        // Bounded: never longer than half an hour, however many failures.
        assertEquals(SourceCooldownPolicy.MAX_COOLDOWN_MS, SourceCooldownPolicy.cooldownFor(20))
        assertEquals(SourceCooldownPolicy.MAX_COOLDOWN_MS, SourceCooldownPolicy.cooldownFor(200))
    }

    @Test
    fun `a failed source is parked, then eligible again without being deleted`() {
        val (store, file) = store()
        try {
            val now = 1_000_000L
            store.recordFailure("sluhayua", now)

            assertFalse("parked right after the failure", store.isEligible("sluhayua", now))
            assertFalse(store.isEligible("sluhayua", now + 59_999L))
            assertTrue("eligible once the window passes", store.isEligible("sluhayua", now + 60_000L))

            // The record is KEPT (the source is not deleted), only paused.
            assertEquals(1, store.record("sluhayua")?.consecutiveFailures)
            assertTrue(store.isEligible("soundbooks", now))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `repeated failures extend the window but never beyond the cap`() {
        val (store, file) = store()
        try {
            var now = 1_000_000L
            store.recordFailure("sluhayua", now)
            now += 60_000L
            store.recordFailure("sluhayua", now)
            assertEquals(2, store.record("sluhayua")?.consecutiveFailures)
            assertFalse(store.isEligible("sluhayua", now + 119_999L))
            assertTrue(store.isEligible("sluhayua", now + 120_000L))

            repeat(10) {
                now += 60_000L
                store.recordFailure("sluhayua", now)
            }
            val record = store.record("sluhayua")!!
            assertEquals(SourceCooldownPolicy.MAX_COOLDOWN_MS, record.cooldownUntil - now)
            assertTrue("still never deleted", store.load().containsKey("sluhayua"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `a success clears the cooldown immediately`() {
        val (store, file) = store()
        try {
            val now = 1_000_000L
            store.recordFailure("sluhayua", now)
            assertFalse(store.isEligible("sluhayua", now))

            store.recordSuccess("sluhayua")

            assertNull(store.record("sluhayua"))
            assertTrue(store.isEligible("sluhayua", now))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `the record survives a restart and a broken file is empty`() {
        val file = File.createTempFile("source-cooldown", ".tsv").apply { delete() }
        try {
            val now = 1_000_000L
            SourceCooldownStore(file).recordFailure("lihtar", now)

            // A NEW instance (a restart) still knows the source is parked.
            val reopened = SourceCooldownStore(file)
            assertFalse(reopened.isEligible("lihtar", now + 1_000L))
            assertEquals(1, reopened.record("lihtar")?.consecutiveFailures)

            file.writeText("garbage without tabs")
            assertTrue(SourceCooldownStore(file).load().isEmpty())
        } finally {
            file.delete()
        }
    }
}
