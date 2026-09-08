package com.slukhayka.audiobooks.ui

import com.slukhayka.audiobooks.data.catalog.SourceCatalog
import com.slukhayka.audiobooks.data.entries.LibraryEntries
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookDetailSourceStateTest {

    @Test
    fun `old refresh cannot finish a reopened book loading state`() {
        val state = BookDetailSourceState()
        val firstA = state.select("a")
        val b = state.select("b")
        val secondA = state.select("a")
        state.finishRefresh(firstA)
        state.finishRefresh(b)
        assertTrue(state.refreshing.value)
        state.finishRefresh(secondA)
        assertFalse(state.refreshing.value)
    }

    @Test
    fun `closing book clears loading and late completion cannot restart it`() {
        val state = BookDetailSourceState()
        val request = state.select("a")
        assertTrue(state.refreshing.value)
        state.select(null)
        state.finishRefresh(request)
        assertFalse(state.refreshing.value)
    }

    @Test
    fun `switching books clears visible claims and rejects late results`() {
        val state = BookDetailSourceState()
        val oldProfile = LibraryEntries.SourceProfile(
            sourceId = "old",
            sourceName = "Old source",
            url = "https://old.example/book",
            description = "Старий опис"
        )
        val oldSource = SourceCatalog.WorkSourceRow(
            sourceId = "old",
            sourceName = "Old source",
            url = oldProfile.url,
            streamOnly = false
        )

        state.select("old-book")
        assertTrue(state.acceptProfiles("old-book", listOf(oldProfile)))
        assertTrue(state.acceptSources("old-book", listOf(oldSource)))

        state.select("new-book")

        assertTrue(state.profiles.value.isEmpty())
        assertTrue(state.sources.value.isEmpty())
        assertFalse(state.acceptProfiles("old-book", listOf(oldProfile)))
        assertFalse(state.acceptSources("old-book", listOf(oldSource)))
        assertTrue(state.profiles.value.isEmpty())
        assertTrue(state.sources.value.isEmpty())
    }
}
