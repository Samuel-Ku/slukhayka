package com.slukhayka.audiobooks.data.duration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * spec-18 T1 (#112) — the pure short/long bucketing: «Короткі» (under
 * 5 hours) and «Довгі» (10 hours and up), middle band not surfaced, unknown
 * durations excluded. Thresholds are module constants, pinned here — the
 * division is a one-line decision.
 */
class DurationBucketsTest {

    private fun book(id: String, seconds: Long) = DurationBook(id, seconds)

    // ------------------------------------------------------------------
    // hasKnownDuration — the honest-data gate
    // ------------------------------------------------------------------

    @Test
    fun `a positive duration is known`() {
        assertTrue(DurationBuckets.hasKnownDuration(1L))
        assertTrue(DurationBuckets.hasKnownDuration(17999L))
        assertTrue(DurationBuckets.hasKnownDuration(18_000L))
    }

    @Test
    fun `zero and negative durations are unknown`() {
        assertFalse(DurationBuckets.hasKnownDuration(0L))
        assertFalse(DurationBuckets.hasKnownDuration(-1L))
    }

    @Test
    fun `the fabricated legacy 4-hour placeholder is unknown`() {
        // Catalogue books were once seeded with a fake 4:00:00 (14400s);
        // it must never render as a real duration.
        assertFalse(DurationBuckets.hasKnownDuration(DurationBuckets.FABRICATED_LEGACY_SECONDS))
    }

    // ------------------------------------------------------------------
    // Boundary values — 4:59:59 / 5:00:00 / 9:59:59 / 10:00:00
    // ------------------------------------------------------------------

    @Test
    fun `just under five hours lands in the short row`() {
        val rows = DurationBuckets.splitByDuration(listOf(book("a", 17999L)))
        assertEquals(listOf("a"), rows.short.map { it.id })
        assertEquals(emptyList<String>(), rows.long.map { it.id })
    }

    @Test
    fun `exactly five hours is the middle band - not surfaced`() {
        val rows = DurationBuckets.splitByDuration(listOf(book("a", 18_000L)))
        assertEquals(emptyList<String>(), rows.short.map { it.id })
        assertEquals(emptyList<String>(), rows.long.map { it.id })
    }

    @Test
    fun `nine hours fifty-nine is the middle band - not surfaced`() {
        val rows = DurationBuckets.splitByDuration(listOf(book("a", 35_999L)))
        assertEquals(emptyList<String>(), rows.short.map { it.id })
        assertEquals(emptyList<String>(), rows.long.map { it.id })
    }

    @Test
    fun `exactly ten hours lands in the long row`() {
        val rows = DurationBuckets.splitByDuration(listOf(book("a", 36_000L)))
        assertEquals(emptyList<String>(), rows.short.map { it.id })
        assertEquals(listOf("a"), rows.long.map { it.id })
    }

    // ------------------------------------------------------------------
    // Exclusions and empty results
    // ------------------------------------------------------------------

    @Test
    fun `a book with unknown duration never appears in either row`() {
        val unknown = listOf(
            book("zero", 0L),
            book("negative", -5L),
            book("fabricated", DurationBuckets.FABRICATED_LEGACY_SECONDS)
        )
        val rows = DurationBuckets.splitByDuration(unknown)
        assertTrue(rows.short.isEmpty())
        assertTrue(rows.long.isEmpty())
    }

    @Test
    fun `empty input yields two empty rows`() {
        val rows = DurationBuckets.splitByDuration(emptyList())
        assertTrue(rows.short.isEmpty())
        assertTrue(rows.long.isEmpty())
    }

    @Test
    fun `a mixed list splits exactly and keeps the input order`() {
        val rows = DurationBuckets.splitByDuration(
            listOf(
                book("short1", 3600L),     // 1 h
                book("long1", 56_700L),    // 15.75 h
                book("middle", 21_600L),   // 6 h → gone
                book("short2", 17_999L),   // 4:59:59
                book("unknown", 0L),       // gone
                book("long2", 36_000L)     // exactly 10 h
            )
        )
        assertEquals(listOf("short1", "short2"), rows.short.map { it.id })
        assertEquals(listOf("long1", "long2"), rows.long.map { it.id })
    }

    @Test
    fun `threshold constants are the documented five and ten hours`() {
        assertEquals(5L * 60 * 60, DurationBuckets.SHORT_ROW_MAX_SECONDS.toLong())
        assertEquals(10L * 60 * 60, DurationBuckets.LONG_ROW_MIN_SECONDS.toLong())
    }
}