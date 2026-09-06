package com.slukhayka.audiobooks.data.reviews

import org.junit.Assert.*
import org.junit.Test

class MemoryFeedbackPreferences : FeedbackPreferences {
    private val values = mutableMapOf<String, String?>()
    private var ids = emptySet<String>()
    override fun read(key: String) = values[key]
    override fun write(values: Map<String, String?>) { this.values.putAll(values) }
    override fun pending() = ids
    override fun pending(ids: Set<String>) { this.ids = ids.toSet() }
}

class BookFeedbackStoreTest {
    @Test fun backgroundCompletionsSurviveRestartAndNeverReplaceAnotherBook() {
        val prefs = MemoryFeedbackPreferences()
        val first = BookFeedbackStore(prefs)
        first.completed("a"); first.completed("b"); first.completed("a")
        val restarted = BookFeedbackStore(prefs)
        assertEquals(setOf("a", "b"), restarted.pending.value)
        restarted.dismiss("a")
        restarted.completed("a")
        assertEquals(setOf("b"), BookFeedbackStore(prefs).pending.value)
    }
    @Test fun dismissingKeepsIndependentDraftsForManualRetry() {
        val prefs = MemoryFeedbackPreferences()
        val store = BookFeedbackStore(prefs)
        store.saveDraft("a", BookFeedbackDraft(3, 5, "Гарна озвучка"))
        store.saveDraft("b", BookFeedbackDraft(1, 2, "Інша книга"))
        store.dismiss("a")
        val restarted = BookFeedbackStore(prefs)
        assertEquals(BookFeedbackDraft(3, 5, "Гарна озвучка"), restarted.draft("a"))
        assertEquals(1, restarted.draft("b")?.bookRating)
        assertNull(restarted.draft("missing"))
    }
}
