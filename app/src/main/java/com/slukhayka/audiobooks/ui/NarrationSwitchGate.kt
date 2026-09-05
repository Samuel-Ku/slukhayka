package com.slukhayka.audiobooks.ui

import com.slukhayka.audiobooks.data.EditionId
import com.slukhayka.audiobooks.data.catalog.CatalogBook
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.source.GlobalSearchResult

/**
 * The minimum identity needed to keep a Source change invisible while making
 * a real Edition change explicit. A blank key means the catalogue has not
 * asserted enough identity to warn truthfully.
 */
data class NarrationSwitchIdentity(
    val workKey: String,
    val editionKey: String,
    val narrator: String,
    val title: String
)

data class NarrationSwitchPrompt(
    val currentNarrator: String,
    val targetNarrator: String,
    val title: String,
    val targetEditionKey: String
)

fun narrationSwitchIdentity(book: AudiobookEntity): NarrationSwitchIdentity {
    val workKey = book.mergeKey.ifBlank { book.workId.orEmpty() }
    val editionKey = if (workKey.isBlank() || book.narrator.isBlank()) "" else {
        EditionId.forBook(workKey, book.id, book.narrator, book.language)
    }
    return NarrationSwitchIdentity(workKey, editionKey, book.narrator, book.title)
}

fun narrationSwitchIdentity(result: GlobalSearchResult): NarrationSwitchIdentity {
    val workKey = result.mergeKey
    val assertedEditions = result.sources
        .map { it.editionId }
        .filter { it.isNotBlank() }
        .distinct()
    val editionKey = when {
        assertedEditions.size == 1 -> assertedEditions.single()
        assertedEditions.size > 1 -> ""
        workKey.isBlank() || result.narrator.isBlank() -> ""
        else -> EditionId.forBook(workKey, result.key, result.narrator, result.language)
    }
    return NarrationSwitchIdentity(workKey, editionKey, result.narrator, result.title)
}

fun narrationSwitchIdentity(book: CatalogBook): NarrationSwitchIdentity {
    val workKey = book.mergeKey.ifBlank { book.workId.orEmpty() }
    val editionKey = if (workKey.isBlank() || book.narrator.isBlank()) "" else {
        EditionId.forBook(workKey, book.id, book.narrator)
    }
    return NarrationSwitchIdentity(workKey, editionKey, book.narrator, book.title)
}

/**
 * Different Sources of one Edition are recovery, not a narration switch.
 * Conversely, two asserted Editions of one Work never share listening state
 * and therefore require an explicit listener choice.
 */
fun requiresNarrationSwitchConfirmation(
    current: NarrationSwitchIdentity?,
    target: NarrationSwitchIdentity,
    approvedEditionKey: String? = null
): Boolean {
    current ?: return false
    if (current.workKey.isBlank() || target.workKey.isBlank()) return false
    if (current.workKey != target.workKey) return false
    if (current.editionKey.isBlank() || target.editionKey.isBlank()) return false
    if (current.editionKey == target.editionKey) return false
    return target.editionKey != approvedEditionKey
}
