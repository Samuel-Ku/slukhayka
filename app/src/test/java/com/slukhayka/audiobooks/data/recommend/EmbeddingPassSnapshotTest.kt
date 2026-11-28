package com.slukhayka.audiobooks.data.recommend

import org.junit.Assert.*
import org.junit.Test

class EmbeddingPassSnapshotTest {
    private fun signal(title: String = "Book", weight: Double = -1.0) =
        RecommendationEngine.Signal("book", title, "Author", weight = weight)
    private val context = EmbeddingContext("identified-model-v2", 2)

    @Test fun `negative feedback is requested once then shares the attempted pass`() {
        val negative = signal()
        val previous = EmbeddingPassSnapshot(context, mapOf("book" to floatArrayOf(1f, 0f)), ready = true)
        assertTrue(previous.needsRefresh(listOf(negative)))
        val refreshed = previous.copy(attemptedSignalTexts = mapOf(negative.id to setOf(negative.text)))
        assertFalse(refreshed.needsRefresh(listOf(negative)))
    }

    @Test fun `failed same input does not create an endless refresh loop`() {
        val positive = signal(weight = .9)
        val snapshot = EmbeddingPassSnapshot(context, emptyMap(), mapOf(positive.id to setOf(positive.text)), true)
        assertFalse(snapshot.needsRefresh(listOf(positive)))
    }

    @Test fun `changed signal text requests a new coherent pass despite an older vector`() {
        val previous = signal()
        val snapshot = EmbeddingPassSnapshot(context, mapOf("book" to floatArrayOf(1f, 0f)),
            mapOf(previous.id to setOf(previous.text)), true)
        assertTrue(snapshot.needsRefresh(listOf(signal("Changed book"))))
    }

    @Test fun `positive and negative text for one Work are both recorded without reruns`() {
        val positive = RecommendationEngine.Signal("book", "Book", "Author", genre = "Classic", weight = .9)
        val negative = signal()
        val snapshot = EmbeddingPassSnapshot(context, mapOf("book" to floatArrayOf(1f, 0f)),
            mapOf("book" to setOf(positive.text, negative.text)), true)
        assertFalse(snapshot.needsRefresh(listOf(positive, negative)))
        assertTrue(snapshot.needsRefresh(listOf(positive.copy(genre = "Drama"), negative)))
    }

    @Test fun `a published context cannot claim vectors from a different dimensional space`() {
        assertThrows(IllegalArgumentException::class.java) {
            EmbeddingPassSnapshot(context, mapOf("book" to FloatArray(384) { 1f }), ready = true)
        }
    }
}
