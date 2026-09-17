package com.slukhayka.audiobooks.data.db

import com.slukhayka.audiobooks.data.entries.ReadingFormat
import com.slukhayka.audiobooks.data.entries.ReadingState
import com.slukhayka.audiobooks.data.entries.ReadingUnit
import com.slukhayka.audiobooks.data.entries.ReadingUnits
import com.slukhayka.audiobooks.data.entries.Readthrough
import com.slukhayka.audiobooks.data.entries.ReadthroughProgressEntry

/**
 * ADR-0046 / #863 — the row <-> domain mapping of a Readthrough.
 *
 * It is deliberately STRICT: an unknown format, state or unit decodes to null
 * instead of the nearest guess — a row the app cannot classify is not silently
 * rewritten into a fiction (ADR-0014). The journal is a compact, self-contained
 * JSON array (`[{"at":1,"u":"PAGES","v":2}]`) so the column needs no library.
 */
internal object ReadthroughMapping {

    fun Readthrough.toEntity(): ReadthroughEntity = ReadthroughEntity(
        id = id,
        libraryEntryId = libraryEntryId,
        workId = workId,
        format = format.name,
        state = state.name,
        startedAt = startedAt,
        finishedAt = finishedAt,
        editionId = editionId,
        unit = units.unit.name,
        unitValue = units.value,
        journalJson = encodeJournal(journal)
    )

    fun ReadthroughEntity.toModelOrNull(): Readthrough? {
        val format = enumOrNull<ReadingFormat>(format) ?: return null
        val state = enumOrNull<ReadingState>(state) ?: return null
        val unit = enumOrNull<ReadingUnit>(unit) ?: return null
        return Readthrough(
            id = id,
            libraryEntryId = libraryEntryId,
            workId = workId,
            format = format,
            state = state,
            startedAt = startedAt,
            finishedAt = finishedAt,
            editionId = editionId,
            units = ReadingUnits(unit, unitValue),
            journal = decodeJournal(journalJson)
        )
    }

    fun encodeJournal(entries: List<ReadthroughProgressEntry>): String =
        entries.joinToString(prefix = "[", postfix = "]", separator = ",") { entry ->
            """{"at":${entry.at},"u":"${entry.units.unit.name}","v":${entry.units.value}}"""
        }

    /**
     * An unreadable journal entry is DROPPED, not invented: the pass keeps the
     * records it can prove, and a corrupt tail can never become a fake number.
     */
    fun decodeJournal(raw: String): List<ReadthroughProgressEntry> {
        if (raw.isBlank() || raw == "[]") return emptyList()
        val pattern = Regex("""\{"at":(\d+),"u":"([A-Z_]+)","v":(\d+)}""")
        return pattern.findAll(raw).mapNotNull { match ->
            val at = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            val unit = enumOrNull<ReadingUnit>(match.groupValues[2]) ?: return@mapNotNull null
            val value = match.groupValues[3].toIntOrNull() ?: return@mapNotNull null
            ReadthroughProgressEntry(at, ReadingUnits(unit, value))
        }.toList()
    }

    private inline fun <reified T : Enum<T>> enumOrNull(name: String): T? =
        enumValues<T>().firstOrNull { it.name == name }
}
