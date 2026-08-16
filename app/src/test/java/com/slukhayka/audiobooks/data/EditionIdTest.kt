package com.slukhayka.audiobooks.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Pure tests for the deterministic Edition id (ADR-0007 + ADR-0010).
 *
 * ADR-0010 — the narrator is part of the edition id because the Work key no
 * longer carries it: two narrations of one Work must resolve to DIFFERENT
 * editions, or they would share listening state (ADR-0001 — incompatible
 * narrations never share timestamps).
 */
class EditionIdTest {

    @Test
    fun `same work and narrator and language - same edition`() {
        assertEquals(
            EditionId.forBook("кобзар|тарас шевченко", "b1", "Валерій Завалко"),
            EditionId.forBook("кобзар|тарас шевченко", "b1", "Валерій Завалко")
        )
    }

    @Test
    fun `different narrators of the same work - different editions`() {
        // ADR-0010 + ADR-0001: the narrator is a rendition property — two
        // narrations of the same text keep separate editions and thus
        // separate progress/bookmarks.
        assertNotEquals(
            EditionId.forBook("кобзар|тарас шевченко", "b1", "Валерій Завалко"),
            EditionId.forBook("кобзар|тарас шевченко", "b1", "Богдан Бенюк")
        )
    }

    @Test
    fun `narrator-free and narrator-ful books differ when narrators differ`() {
        assertNotEquals(
            EditionId.forBook("кобзар|тарас шевченко", "b1", ""),
            EditionId.forBook("кобзар|тарас шевченко", "b1", "Валерій Завалко")
        )
    }

    @Test
    fun `blank key falls back to the book id and still carries the narrator`() {
        assertNotEquals(
            EditionId.forBook("", "local-1"),
            EditionId.forBook("", "local-1", "Локальний аудіофайл")
        )
    }

    @Test
    fun `language keeps editions apart`() {
        assertNotEquals(
            EditionId.forBook("кобзар|тарас шевченко", "b1", "Валерій Завалко", ""),
            EditionId.forBook("кобзар|тарас шевченко", "b1", "Валерій Завалко", "uk")
        )
    }

    @Test
    fun `the legacy formula differs from the ADR-0010 formula`() {
        // The v13→v14-era id hashed the narrator INSIDE the mergeKey; the
        // ADR-0010 id hashes it as a separate input — the v16 migration
        // remaps every legacy id to the new one.
        assertNotEquals(
            EditionId.forBook("кобзар|тарас шевченко|валерій завалко", "b1"),
            EditionId.forBookLegacy("кобзар|тарас шевченко|валерій завалко", "b1")
        )
    }
}
