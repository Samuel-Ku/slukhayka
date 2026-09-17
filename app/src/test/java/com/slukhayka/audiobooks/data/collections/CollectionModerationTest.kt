package com.slukhayka.audiobooks.data.collections

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec-51 (#696) — the threshold and the one-way hidden rule. */
class CollectionModerationTest {

    @Test
    fun `the third unique complaint hides the collection`() {
        assertFalse(CollectionModeration.nextHidden(currentHidden = false, currentCount = 0))
        assertFalse(CollectionModeration.nextHidden(currentHidden = false, currentCount = 1))
        assertTrue(CollectionModeration.nextHidden(currentHidden = false, currentCount = 2))
    }

    @Test
    fun `hidden is one-way - it never turns back off`() {
        assertTrue(CollectionModeration.nextHidden(currentHidden = true, currentCount = 3))
        assertTrue(CollectionModeration.nextHidden(currentHidden = true, currentCount = 0))
    }

    @Test
    fun `a hidden collection is never a public surface`() {
        assertTrue(CollectionModeration.isRenderable(sample(hidden = false)))
        assertFalse(CollectionModeration.isRenderable(sample(hidden = true)))
    }

    private fun sample(hidden: Boolean) = PublishedCollection(
        authorId = "a".repeat(64),
        collectionId = "c1",
        pseudonym = "Слухач",
        title = "Магія",
        description = "",
        bookIds = listOf("book-a"),
        reasons = listOf(""),
        hidden = hidden,
        publishedAt = 1L
    )
}
