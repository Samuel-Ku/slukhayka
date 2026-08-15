package com.example.data.metadata

import com.example.data.source.SourceChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure JVM seam (ADR-0004): the ONE place Metadata Assertions are applied to
 * library rows. Table-driven — every rule is a pure function of the claim,
 * no I/O, so the JVM suite pins each boundary without Robolectric.
 */
class MetadataAssertionsTest {

    // --- Sentinel normalization -------------------------------------------

    @Test
    fun `duration claims normalize - blank zero negative and legacy sentinel are unknown`() {
        val cases = listOf<Pair<Long?, Long?>>(
            null to null,
            0L to null,
            -5L to null,
            MetadataAssertions.LEGACY_SENTINEL_DURATION_SECONDS to null,
            1L to 1L,
            3_600L to 3_600L,
            14_401L to 14_401L
        )
        cases.forEach { (claimed, expected) ->
            assertEquals("normalizeDurationSeconds($claimed)", expected, MetadataAssertions.normalizeDurationSeconds(claimed))
        }
    }

    // --- Brand-scrub ------------------------------------------------------

    @Test
    fun `claimed text normalizes - blank and brand placeholders are absent`() {
        val cases = listOf<Pair<String?, String?>>(
            null to null,
            "" to null,
            "   " to null,
            "4read.org" to null,
            "Аудиокнига 4read.org" to null,
            "4READ Voice Narrator" to null,
            "Жан-Крістоф Гранже" to "Жан-Крістоф Гранже",
            "  Тарас Шевченко  " to "Тарас Шевченко"
        )
        cases.forEach { (claimed, expected) ->
            assertEquals("normalizeClaimedText($claimed)", expected, MetadataAssertions.normalizeClaimedText(claimed))
        }
    }

    // --- Never-clobber duration -------------------------------------------

    @Test
    fun `duration delta never clobbers a known value with an unknown claim`() {
        val cases = listOf<Pair<Long?, Long>>(
            null to 3_600L,        // unknown claim keeps existing
            0L to 3_600L,
            MetadataAssertions.LEGACY_SENTINEL_DURATION_SECONDS to 3_600L,
            7_200L to 7_200L,      // real claim wins
            3_600L to 3_600L,      // same value — no change
            7_200L to 7_200L       // real claim over sentinel-existing? existing already normalized
        )
        cases.forEach { (claimed, expected) ->
            assertEquals(
                "durationDelta(3600, $claimed)",
                expected,
                MetadataAssertions.durationDelta(3_600L, claimed)
            )
        }
        // Sentinel stored on the existing row is also treated as unknown.
        assertEquals(
            7_200L,
            MetadataAssertions.durationDelta(MetadataAssertions.LEGACY_SENTINEL_DURATION_SECONDS, 7_200L)
        )
    }

    // --- Series-on-URL-change ---------------------------------------------

    @Test
    fun `series applies only when its URL changed`() {
        // No claim → nothing.
        assertNull(MetadataAssertions.seriesDelta("https://s/1", null, "Цикл", 1))
        // Same URL → nothing (title/index updates are not a membership signal).
        assertNull(MetadataAssertions.seriesDelta("https://s/1", "https://s/1", "Цикл", 1))
        // URL changed → delta carries the new values.
        val changed = MetadataAssertions.seriesDelta("https://s/1", "https://s/2", "Цикл", 2)
        assertEquals("https://s/2", changed?.url)
        assertEquals("Цикл", changed?.title)
        assertEquals(2, changed?.index)
        // First series (no existing URL) → applies.
        val first = MetadataAssertions.seriesDelta(null, "https://s/2", "Цикл", 1)
        assertEquals("https://s/2", first?.url)
        // URL changed, title unknown → url still updates, title stays null.
        val noTitle = MetadataAssertions.seriesDelta("https://s/1", "https://s/2", null, null)
        assertEquals("https://s/2", noTitle?.url)
        assertNull(noTitle?.title)
    }

    // --- Cover-only-non-blank ---------------------------------------------

    @Test
    fun `cover applies only when non-blank and never brand-scrubbed`() {
        assertNull(MetadataAssertions.coverDelta(null))
        assertNull(MetadataAssertions.coverDelta(""))
        assertNull(MetadataAssertions.coverDelta("   "))
        // Cover URLs may legitimately live on the source's own domain.
        assertEquals(
            "https://4read.org/uploads/cover.jpg",
            MetadataAssertions.coverDelta(" https://4read.org/uploads/cover.jpg ")
        )
    }

    // --- Chapter materialization ------------------------------------------

    @Test
    fun `chapter materialization uses the dash id format, one title fallback and duration conventions`() {
        val chapters = MetadataAssertions.materializeChapters(
            bookId = "b1",
            bookTitle = "Пасажир",
            chapters = listOf(
                SourceChapter("Розділ 1", "https://s/1.mp3", durationSeconds = 600L),
                SourceChapter("", "https://s/2.mp3", durationSeconds = MetadataAssertions.LEGACY_SENTINEL_DURATION_SECONDS),
                SourceChapter("  ", "https://s/3.mp3", durationSeconds = 0L)
            )
        )

        // The dash id format — the single format for all new books.
        assertEquals(listOf("b1_ch_1", "b1_ch_2", "b1_ch_3"), chapters.map { it.id })
        // Real title kept; the one blank-title fallback carries the book title.
        assertEquals("Розділ 1", chapters[0].title)
        assertEquals("Глава 2 (Пасажир)", chapters[1].title)
        assertEquals("Глава 3 (Пасажир)", chapters[2].title)
        // Duration convention: real claim survives, sentinel/zero become 0.
        assertEquals(listOf(600L, 0L, 0L), chapters.map { it.durationSeconds })
        // Indexes and stream urls intact.
        assertEquals(listOf(0, 1, 2), chapters.map { it.chapterIndex })
        assertEquals("https://s/3.mp3", chapters[2].streamUrl)
    }
}
