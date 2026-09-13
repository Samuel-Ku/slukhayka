package com.slukhayka.audiobooks.data.collections

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForkAttributionTest {

    private fun original() = ListenerCollection(
        id = "c1",
        title = "Магія",
        description = "про зорі",
        createdAt = 1L,
        items = listOf(
            ListenerCollectionItem("a", "бо атмосферно", 1L),
            ListenerCollectionItem("b", "", 2L)
        )
    )

    @Test
    fun `the fork is a snapshot and later edits to the original change nothing`() {
        val (fork, _) = ForkPolicy.forkOf(original(), "Слухач", "doc-1", now = 100L)

        val edited = original().copy(title = "Інша назва", items = emptyList())
        assertEquals("Магія", fork.title)
        assertEquals(listOf("a", "b"), fork.items.map { it.bookId })
        assertEquals("бо атмосферно", fork.items.first().reason)
        assertEquals("the original's edit is a different object", emptyList<String>(), edited.items.map { it.bookId })
    }

    @Test
    fun `the attribution text names the original and its curator`() {
        val (_, attribution) = ForkPolicy.forkOf(original(), "Слухач", "doc-1", now = 100L)
        assertEquals("на основі «Магія» від Слухач", attribution.text)
    }

    @Test
    fun `the link lives only while the original is visible, the text always`() {
        val (_, attribution) = ForkPolicy.forkOf(original(), "Слухач", "doc-1", now = 100L)

        assertTrue(attribution.linkAvailable(originalVisible = true))
        assertFalse(attribution.linkAvailable(originalVisible = false))
        // The TEXT is a snapshot and does not depend on visibility at all.
        assertEquals("на основі «Магія» від Слухач", attribution.text)
    }

    @Test
    fun `a fork becomes a normal own collection with fresh identity and time`() {
        val (fork, _) = ForkPolicy.forkOf(original(), "Слухач", "doc-1", now = 100L)
        assertTrue(fork.id != original().id)
        assertEquals(100L, fork.createdAt)
        assertEquals(2, fork.items.size)
    }
}
