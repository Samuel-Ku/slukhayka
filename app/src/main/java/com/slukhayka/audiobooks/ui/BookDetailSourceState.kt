package com.slukhayka.audiobooks.ui

import com.slukhayka.audiobooks.data.catalog.SourceCatalog
import com.slukhayka.audiobooks.data.entries.LibraryEntries
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Book-scoped async Source state; late results can never cross Work pages. */
internal class BookDetailSourceState {
    private var selectedBookId: String? = null

    private val _profiles = MutableStateFlow<List<LibraryEntries.SourceProfile>>(emptyList())
    val profiles: StateFlow<List<LibraryEntries.SourceProfile>> = _profiles.asStateFlow()

    private val _sources = MutableStateFlow<List<SourceCatalog.WorkSourceRow>>(emptyList())
    val sources: StateFlow<List<SourceCatalog.WorkSourceRow>> = _sources.asStateFlow()

    fun select(bookId: String?) {
        selectedBookId = bookId
        _profiles.value = emptyList()
        _sources.value = emptyList()
    }

    fun acceptProfiles(bookId: String, profiles: List<LibraryEntries.SourceProfile>): Boolean {
        if (selectedBookId != bookId) return false
        _profiles.value = profiles
        return true
    }

    fun acceptSources(bookId: String, sources: List<SourceCatalog.WorkSourceRow>): Boolean {
        if (selectedBookId != bookId) return false
        _sources.value = sources
        return true
    }
}
