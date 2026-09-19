package com.slukhayka.audiobooks.data.metadata

import com.slukhayka.audiobooks.data.db.CorrectionEntity
import com.slukhayka.audiobooks.data.db.CorrectionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-0053 / #855 (T2) — the pure rule of the cover Override: how a listener's
 * decision is remembered (the existing FIELD correction grammar) and who wins
 * when a claim arrives. No Room, no Compose — the precedence itself is the
 * unit under test.
 */
class CoverOverrideTest {

    private fun field(value: String, updatedAt: Long = 0L) = CorrectionEntity(
        mergeKey = "кобзар|шевченко",
        kind = CorrectionKind.FIELD,
        value = value,
        updatedAt = updatedAt
    )

    @Test
    fun `a pinned cover round-trips through the correction value`() {
        assertEquals("cover=https://x.example/c.jpg", CoverOverride.encoded("https://x.example/c.jpg"))
        assertEquals("cover=", CoverOverride.encoded(null))
        // Whitespace is not a decision.
        assertEquals("cover=", CoverOverride.encoded("   "))
        assertEquals(
            CoverOverride.Pinned("https://x.example/c.jpg"),
            CoverOverride.pinned(listOf(field("cover=https://x.example/c.jpg")))
        )
    }

    @Test
    fun `a pinned absence decodes to an honest null cover`() {
        assertEquals(CoverOverride.Pinned(null), CoverOverride.pinned(listOf(field("cover="))))
    }

    @Test
    fun `other corrections are never mistaken for a cover decision`() {
        assertNull(CoverOverride.pinned(emptyList()))
        assertNull(CoverOverride.pinned(listOf(field("title=Кобзар"))))
        assertNull(
            CoverOverride.pinned(
                listOf(
                    CorrectionEntity(mergeKey = "w", kind = CorrectionKind.NEVER_MATCH, value = "cover=x"),
                    CorrectionEntity(mergeKey = "w", kind = CorrectionKind.SPLIT, value = "cover=x")
                )
            )
        )
    }

    @Test
    fun `the newest decision wins`() {
        val older = field("cover=https://old.example/c.jpg", updatedAt = 10L)
        val newer = field("cover=https://new.example/c.jpg", updatedAt = 20L)

        assertEquals(CoverOverride.Pinned("https://new.example/c.jpg"), CoverOverride.pinned(listOf(older, newer)))
        assertEquals(CoverOverride.Pinned("https://new.example/c.jpg"), CoverOverride.pinned(listOf(newer, older)))
        // A newer ABSENCE outranks an older URL — clearing the cover is a decision.
        assertEquals(
            CoverOverride.Pinned(null),
            CoverOverride.pinned(listOf(older, field("cover=", updatedAt = 20L)))
        )
    }

    @Test
    fun `an Override outranks the local value and the claim`() {
        val pinned = CoverOverride.Pinned("https://mine.example/c.jpg")

        assertEquals(
            "the pinned URL wins over both",
            "https://mine.example/c.jpg",
            CoverOverride.over(pinned, local = "https://local.example/c.jpg", claimed = "https://claim.example/c.jpg")
        )
        assertEquals(
            "a pinned absence beats both with an honest null",
            null,
            CoverOverride.over(CoverOverride.Pinned(null), "https://local.example/c.jpg", "https://claim.example/c.jpg")
        )
        assertTrue(CoverOverride.blocksWrite(pinned))
        assertTrue(CoverOverride.blocksWrite(CoverOverride.Pinned(null)))
        assertFalse(CoverOverride.blocksWrite(null))
    }

    @Test
    fun `with no Override the local value still outranks the claim`() {
        assertEquals(
            "https://local.example/c.jpg",
            CoverOverride.over(null, local = "https://local.example/c.jpg", claimed = "https://claim.example/c.jpg")
        )
        assertEquals(
            "a blank local value is not a known cover",
            "https://claim.example/c.jpg",
            CoverOverride.over(null, local = "  ", claimed = "https://claim.example/c.jpg")
        )
        assertNull(CoverOverride.over(null, local = null, claimed = null))
    }
}
