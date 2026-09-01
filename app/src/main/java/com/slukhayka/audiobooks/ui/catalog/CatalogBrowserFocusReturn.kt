package com.slukhayka.audiobooks.ui.catalog

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One-shot focus return from the listener-initiated Browser Source screen. */
object CatalogBrowserFocusReturn {
    private var pendingCardKey: String? = null
    private val _returnCardKey = MutableStateFlow<String?>(null)
    val returnCardKey: StateFlow<String?> = _returnCardKey.asStateFlow()

    fun remember(cardKey: String) {
        pendingCardKey = cardKey
    }

    fun publishAfterBrowserClose() {
        _returnCardKey.value = pendingCardKey
        pendingCardKey = null
    }

    fun consume(cardKey: String) {
        if (_returnCardKey.value == cardKey) _returnCardKey.value = null
    }
}
