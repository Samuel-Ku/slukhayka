package com.slukhayka.audiobooks.data.db

/**
 * #734 / ADR-0041 — one series row the listener actually has a book in:
 * the title and its catalogue URL (null for a local/curated series). The
 * «Серії» index aggregates these, never a source section.
 */
data class SeriesIndexRow(
    val title: String,
    val url: String?
)
