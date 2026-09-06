package com.slukhayka.audiobooks.ui

import com.slukhayka.audiobooks.data.db.BookRow
import com.slukhayka.audiobooks.data.identity.ListenerProfile
import com.slukhayka.audiobooks.data.reviews.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class BookFeedbackControllerTest {
    private val book = BookRow("row", "Книга", "Автор", "Озвучувач", "", 0, genre = "", sourceUrl = "", workId = "work")
    private val reviewStore = object : ListenerReviewsStore {
        val documents = mutableMapOf<String, Map<String, Any>>()
        override suspend fun queryWorkDocuments(workId: String) = documents.values.toList()
        override suspend fun queryWorksDocuments(workIds: List<String>) = documents.values.toList()
        override suspend fun enqueueDocument(documentId: String, document: Map<String, Any>): ReviewWriteReceipt {
            documents[documentId] = document
            return ReviewWriteReceipt.Queued { ReviewRemoteResult.PUBLISHED }
        }
        override suspend fun removeDocument(documentId: String) = true
    }
    private val narrationStore = object : NarrationRatingsStore {
        var accept = false
        val documents = mutableMapOf<String, Map<String, Any>>()
        override suspend fun queryWorkDocuments(workId: String) = documents.values.toList()
        override suspend fun setDocument(documentId: String, document: Map<String, Any>): Boolean {
            if (accept) documents[documentId] = document
            return accept
        }
        override suspend fun removeDocument(documentId: String) = true
    }
    private fun controller(scope: CoroutineScope, store: BookFeedbackStore, available: Boolean = true) = BookFeedbackController(
        scope, store, { book }, { "edition" }, { ListenerProfile("uid", "Ім’я") },
        if (available) reviewStore else null, if (available) narrationStore else null)
    private suspend fun ready(c: BookFeedbackController) = withTimeout(3_000) { c.state.first { it != null && !it.loading }!! }

    @Test fun partialFailureRetainsDraftAndRetryUsesTheSameWorkAndEditionKeys() = runBlocking {
        val store = BookFeedbackStore(MemoryFeedbackPreferences())
        val c = controller(this, store)
        c.open("row"); ready(c)
        c.edit(BookFeedbackDraft(2, 5, "Текст")); c.save()
        withTimeout(3_000) { c.state.first { it?.failed == true } }
        assertEquals("Текст", store.draft("row")?.body)
        assertEquals("work", reviewStore.documents.values.single()["workId"])
        narrationStore.accept = true
        c.save()
        withTimeout(3_000) { c.state.first { it?.accepted == true } }
        assertEquals(1, reviewStore.documents.size)
        assertEquals("edition", narrationStore.documents.values.single()["editionId"])
        assertEquals(5L, (narrationStore.documents.values.single()["rating"] as Number).toLong())
        assertNull(store.draft("row"))
        c.dismiss()
        assertNull(store.draft("row"))
        reviewStore.enqueueReview(ListenerReview("work", "uid", "Ім’я", 1, body = "Новіший відгук", createdAt = 10))
        c.open("row")
        assertEquals(1, ready(c).draft.bookRating)
        assertEquals("Новіший відгук", c.state.value?.draft?.body)
        store.completed("row"); assertTrue(store.pending.value.isEmpty())
    }

    @Test fun missingServicesKeepAnEditableDraftAcrossClosingAndReopening() = runBlocking {
        val store = BookFeedbackStore(MemoryFeedbackPreferences())
        val c = controller(this, store, available = false)
        c.open("row"); ready(c); c.edit(BookFeedbackDraft(4, 3, "Зберегти")); c.save()
        withTimeout(3_000) { c.state.first { it?.failed == true } }
        c.dismiss(); c.open("row")
        assertEquals(BookFeedbackDraft(4, 3, "Зберегти"), ready(c).draft)
    }

    @Test fun anExistingPairOfRatingsSuppressesTheAutomaticPrompt() = runBlocking {
        reviewStore.enqueueReview(ListenerReview("work", "uid", "Ім’я", 4, createdAt = 10))
        narrationStore.accept = true
        narrationStore.putRating(NarrationRating("work", "uid", "edition", 5, 20))
        val store = BookFeedbackStore(MemoryFeedbackPreferences()); store.completed("row")
        val c = controller(this, store); c.open("row", automatic = true)
        withTimeout(3_000) { store.pending.first { it.isEmpty() } }
        assertNull(c.state.value)
    }
    @Test fun dismissingALoadingPromptDoesNotBlockTheNextCompletedBook() = runBlocking {
        val store = BookFeedbackStore(MemoryFeedbackPreferences())
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val c = BookFeedbackController(this, store, { book.copy(id = it) }, { "edition" }, {
            if (calls.incrementAndGet() == 1) awaitCancellation()
            ListenerProfile("uid", "Ім’я")
        }, reviewStore, narrationStore)
        c.open("first")
        withTimeout(3_000) { c.state.first { it != null } }
        while (calls.get() == 0) delay(1)
        c.dismiss()
        c.open("second", automatic = true)
        assertEquals("second", ready(c).bookId)
    }

}
