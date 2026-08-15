package com.example.ui

import com.example.data.db.AudiobookEntity
import com.example.data.db.ChapterEntity

val AudiobookEntity.displayAuthor: String
    get() = author.ifBlank { "Невідомий автор" }

fun effectiveChapterDurations(
    chapters: List<ChapterEntity>,
    currentChapterIndex: Int,
    currentChapterDurationMs: Long,
    bookTotalDurationSeconds: Long = 0L
): List<Long> {
    if (chapters.isEmpty()) return emptyList()
    return chapters.mapIndexed { idx, ch ->
        if (idx == currentChapterIndex && currentChapterDurationMs > 0) {
            currentChapterDurationMs / 1000L
        } else {
            ch.durationSeconds.coerceAtLeast(1L)
        }
    }
}
