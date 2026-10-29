package com.slukhayka.audiobooks.ui.collections

import com.slukhayka.audiobooks.data.collections.CollectionMatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * #1606 — the «Колекції» index owns a LIVE view of the matched smart
 * collections while it is open.
 *
 * Before this seam `MainViewModel.openCollectionsIndex()` copied
 * `SourceCatalog.smartCollections.value` once: the Огляд kept updating its
 * collection cards (it collects the same flow in composition) while the
 * pushed index stayed frozen at open time. The module keeps that snapshot as
 * the immediate first value (no flicker) and then follows the flow until the
 * screen closes.
 *
 * Injected `scope` keeps the seam testable: production passes
 * `viewModelScope`, tests pass a `TestScope`.
 */
internal class CollectionsIndexModule(
    private val matchedCollections: StateFlow<List<CollectionMatcher.MatchedCollection>>,
    private val scope: CoroutineScope
) {
    private val _isOpen = MutableStateFlow(false)
    val isOpen: StateFlow<Boolean> = _isOpen.asStateFlow()

    private val _collections =
        MutableStateFlow<List<CollectionMatcher.MatchedCollection>>(emptyList())
    val collections: StateFlow<List<CollectionMatcher.MatchedCollection>> =
        _collections.asStateFlow()

    private var collectionJob: Job? = null

    fun open() {
        // The current snapshot is the first frame — the screen never flashes an
        // empty list while the collector is being scheduled.
        _collections.value = matchedCollections.value
        collectionJob?.cancel()
        collectionJob = scope.launch {
            matchedCollections.collect { matched -> _collections.value = matched }
        }
        _isOpen.value = true
    }

    fun close() {
        collectionJob?.cancel()
        collectionJob = null
        _isOpen.value = false
        _collections.value = emptyList()
    }
}
