package com.slukhayka.audiobooks.data.reviews

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Local drafts and pending completion prompts; never a published crowd rating. */
interface FeedbackPreferences {
    fun read(key: String): String?
    fun write(values: Map<String, String?>)
    fun pending(): Set<String>
    fun pending(ids: Set<String>)
}

data class BookFeedbackDraft(val bookRating: Int = 0, val narrationRating: Int = 0, val body: String = "")

class BookFeedbackStore(private val prefs: FeedbackPreferences) {
    private val _pending = MutableStateFlow(prefs.pending())
    val pending = _pending.asStateFlow()

    @Synchronized fun completed(bookId: String) {
        if (prefs.read("handled:$bookId") == "true") return
        updatePending(_pending.value + bookId)
    }

    @Synchronized fun dismiss(bookId: String) {
        prefs.write(mapOf("handled:$bookId" to "true"))
        updatePending(_pending.value - bookId)
    }

    private fun updatePending(ids: Set<String>) {
        prefs.pending(ids)
        _pending.value = ids
    }

    fun draft(bookId: String): BookFeedbackDraft? {
        if (prefs.read("draft:$bookId") != "true") return null
        return BookFeedbackDraft(
            prefs.read("book:$bookId")?.toIntOrNull() ?: 0,
            prefs.read("narration:$bookId")?.toIntOrNull() ?: 0,
            prefs.read("body:$bookId").orEmpty()
        )
    }

    fun clearDraft(bookId: String) = prefs.write(mapOf(
        "draft:$bookId" to null, "book:$bookId" to null,
        "narration:$bookId" to null, "body:$bookId" to null
    ))

    fun saveDraft(bookId: String, draft: BookFeedbackDraft) = prefs.write(mapOf(
        "draft:$bookId" to "true", "book:$bookId" to draft.bookRating.toString(),
        "narration:$bookId" to draft.narrationRating.toString(), "body:$bookId" to draft.body
    ))
}
