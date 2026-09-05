package com.slukhayka.audiobooks.ui

import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.BookRow
import com.slukhayka.audiobooks.data.db.DownloadState
import com.slukhayka.audiobooks.data.entries.LibraryEntries
import com.slukhayka.audiobooks.testing.FakeAudiobookDao
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SelectedBookDownloadStateTest {
    @Test
    fun `selected book publishes pause and resume when metadata stays unchanged`() = runTest {
        // Emit immutable joined rows as Room does. FakeAudiobookDao's mutable
        // entity storage itself suppresses projection-only updates.
        val rows = MutableStateFlow(BookRow(
            id = "partial", title = "Книга", author = "Автор", narrator = "Читець",
            description = "", coverDrawableRes = 0, genre = "", sourceUrl = "https://4read.org/book"
        ))
        val dao = object : AudiobookDao by FakeAudiobookDao() {
            override fun observeAudiobookById(id: String) = rows
        }
        val selected = LibraryEntries(dao, emptyList()).observeBookRow("partial")
            .stateIn(backgroundScope, SharingStarted.Eagerly, null)
        runCurrent()
        rows.value = rows.value.copy(downloadProgress = 0.4375f, downloadState = DownloadState.DOWNLOADING)
        runCurrent()
        assertEquals(DownloadState.DOWNLOADING, selected.value?.downloadState)
        rows.value = rows.value.copy(downloadProgress = 0.4375f, downloadState = DownloadState.PAUSED)
        runCurrent()
        assertEquals(DownloadState.PAUSED, selected.value?.downloadState)
        assertEquals(0.4375f, selected.value?.downloadProgress)
        rows.value = rows.value.copy(downloadProgress = 0.4375f, downloadState = DownloadState.DOWNLOADING)
        runCurrent()
        assertEquals(DownloadState.DOWNLOADING, selected.value?.downloadState)
    }
}
