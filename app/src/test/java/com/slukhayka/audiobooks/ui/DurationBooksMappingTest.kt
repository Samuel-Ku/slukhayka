package com.slukhayka.audiobooks.ui

import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.ui.durationBooksFrom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * spec-18 T3 (#114) — the render contract of the «За тривалістю» section:
 * the ViewModel glue [durationBooksFrom] hands the UI exactly the books the
 * pure [com.slukhayka.audiobooks.data.duration.DurationBuckets] module bucketed, as full
 * entities. The bucketing rules themselves are pinned by
 * DurationBucketsTest; this test pins the bucketing→render wiring that the
 * snapshot test intentionally does not exercise (it renders fixture rows).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DurationBooksMappingTest {

    private fun book(id: String, durationSeconds: Long) = AudiobookEntity(
        id = id,
        title = "Книга $id",
        author = "Автор",
        narrator = "",
        description = "",
        coverDrawableRes = 0,
        sourceUrl = "https://4read.org/$id.html",
        genre = "",
        totalDurationSeconds = durationSeconds
    )

    @Test
    fun rows_contain_exactly_the_bucketed_books() {
        val rows = durationBooksFrom(
            listOf(
                book("short-1", 3 * 3600L),
                book("short-2", 4 * 3600L + 59 * 60L + 59),
                book("middle", 6 * 3600L),
                book("long-1", 10 * 3600L),
                book("unknown", 0L)
            )
        )
        assertEquals(listOf("short-1", "short-2"), rows.short.map { it.id })
        assertEquals(listOf("long-1"), rows.long.map { it.id })
    }

    @Test
    fun rows_are_empty_when_nothing_is_bucketed() {
        val rows = durationBooksFrom(emptyList())
        assertTrue(rows.short.isEmpty())
        assertTrue(rows.long.isEmpty())
    }

    @Test
    fun order_within_a_row_preserves_library_order() {
        val rows = durationBooksFrom(
            listOf(
                book("b", 2 * 3600L),
                book("a", 1 * 3600L),
                book("c", 12 * 3600L)
            )
        )
        assertEquals(listOf("b", "a"), rows.short.map { it.id })
        assertEquals(listOf("c"), rows.long.map { it.id })
    }
}