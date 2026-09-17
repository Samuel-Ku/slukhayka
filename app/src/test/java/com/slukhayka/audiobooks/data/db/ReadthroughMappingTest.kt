package com.slukhayka.audiobooks.data.db

import com.slukhayka.audiobooks.data.entries.ReadingFormat
import com.slukhayka.audiobooks.data.entries.ReadingState
import com.slukhayka.audiobooks.data.entries.ReadingUnit
import com.slukhayka.audiobooks.data.entries.ReadthroughPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** ADR-0046 / #863 — the row mapping is strict: unknown values are never guessed. */
class ReadthroughMappingTest {

    private fun pass() = ReadthroughPolicy.start(
        id = "rt-1",
        libraryEntryId = "entry-1",
        workId = "work-1",
        format = ReadingFormat.PAPER,
        startedAt = 1_000L,
        value = 12
    )!!

    @Test
    fun `a readthrough round-trips through its row`() {
        val progressed = ReadthroughPolicy.recordProgress(pass(), at = 2_000L, value = 40)!!
        val entity = with(ReadthroughMapping) { progressed.toEntity() }
        val back = with(ReadthroughMapping) { entity.toModelOrNull() }

        assertEquals(progressed, back)
        assertEquals("PAPER", entity.format)
        assertEquals("PAGES", entity.unit)
        assertEquals(1, back?.journal?.size)
    }

    @Test
    fun `an unknown format, state or unit is a miss, never a guess`() {
        val base = with(ReadthroughMapping) { pass().toEntity() }

        assertNull(with(ReadthroughMapping) { base.copy(format = "SCROLL").toModelOrNull() })
        assertNull(with(ReadthroughMapping) { base.copy(state = "MAYBE").toModelOrNull() })
        assertNull(with(ReadthroughMapping) { base.copy(unit = "FURLONGS").toModelOrNull() })
    }

    @Test
    fun `a corrupt journal entry is dropped, not invented`() {
        val decoded = ReadthroughMapping.decodeJournal(
            """[{"at":2000,"u":"PAGES","v":40},{"at":"x","u":"PAGES","v":1},{"at":3000,"u":"NOPE","v":2}]"""
        )

        assertEquals(1, decoded.size)
        assertEquals(2_000L, decoded.single().at)
        assertEquals(ReadingUnit.PAGES, decoded.single().units.unit)
        assertEquals(40, decoded.single().units.value)
        assertEquals(emptyList<Any>(), ReadthroughMapping.decodeJournal("[]"))
        assertEquals(emptyList<Any>(), ReadthroughMapping.decodeJournal("not json at all"))
    }

    @Test
    fun `an audio pass keeps its edition through the row`() {
        val audio = ReadthroughPolicy.start(
            id = "rt-audio",
            libraryEntryId = "entry-1",
            workId = "work-1",
            format = ReadingFormat.AUDIO,
            startedAt = 1_000L,
            editionId = "edition-1",
            value = 300
        )!!
        val entity = with(ReadthroughMapping) { audio.toEntity() }

        assertEquals("edition-1", entity.editionId)
        assertEquals("SECONDS", entity.unit)
        assertEquals(ReadingState.IN_PROGRESS, with(ReadthroughMapping) { entity.toModelOrNull() }?.state)
    }
}
