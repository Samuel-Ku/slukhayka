package com.slukhayka.audiobooks.data.achievements

/**
 * #702 (T4) — how many library Works carry one normalized genre.
 *
 * A Work-level count on purpose: `work_genres` is keyed by `workId`, so the
 * same genre claimed by two sources for one Work contributes ONE. The row
 * shape exists so the DAO can group in SQL rather than in Kotlin.
 */
data class GenreBookCount(val genreId: String, val works: Long)
