package com.slukhayka.audiobooks.ui

import com.slukhayka.audiobooks.data.catalog.SourceCatalog
import com.slukhayka.audiobooks.data.entries.LibraryEntries
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookDetailSourceStateTest {

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
