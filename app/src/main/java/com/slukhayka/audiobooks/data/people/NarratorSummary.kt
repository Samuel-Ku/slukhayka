package com.slukhayka.audiobooks.data.people

/**
 * #736 / ADR-0041 — one narrator the listener actually has in the Медіатека:
 * the display name carried by an owned Edition's row, and how many owned Works
 * use that narration. The «Виконавці» index reads this, never a provider page.
 */
data class NarratorSummary(
    val displayName: String,
    val workCount: Int
)
