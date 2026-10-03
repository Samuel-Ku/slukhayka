package com.slukhayka.audiobooks.data.search

import com.slukhayka.audiobooks.data.source.GlobalSearchResult

/** A local preview or a settled source answer; neither promises a complete bibliography. */
data class GlobalSearchUpdate(
    val results: List<GlobalSearchResult>,
    val isSearchingSources: Boolean,
    val hasSourceFailures: Boolean = false
)
