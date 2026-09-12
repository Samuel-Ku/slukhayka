package com.slukhayka.audiobooks.ui

import com.slukhayka.audiobooks.data.catalog.CatalogBook
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.source.GlobalSearchResult
import com.slukhayka.audiobooks.data.source.GlobalSearchSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NarrationSwitchGateTest {

    private val current = NarrationSwitchIdentity(
        workKey = "problem-with-peace|john-doe",
        editionKey = "edition-narrator-a",
        narrator = "Диктор А",
        title = "Проблема з миром"
    )

    @Test
    fun `same edition never asks even when source changes`() {
        val target = current.copy(title = "Інша картка того самого видання")

        assertFalse(requiresNarrationSwitchConfirmation(current, target))
    }

    @Test
    fun `different edition of same work asks before switching`() {
        val target = current.copy(
            editionKey = "edition-narrator-b",
            narrator = "Диктор Б"
        )

        assertTrue(requiresNarrationSwitchConfirmation(current, target))
    }

    @Test
    fun `different work does not ask`() {
        val target = current.copy(
            workKey = "another-work|john-doe",
            editionKey = "another-edition"
        )

        assertFalse(requiresNarrationSwitchConfirmation(current, target))
    }

    @Test
    fun `unknown rendition does not create a false warning`() {
        val target = current.copy(editionKey = "", narrator = "")

        assertFalse(requiresNarrationSwitchConfirmation(current, target))
    }

    @Test
    fun `one approval covers later entry point for the same target`() {
        val target = current.copy(
            editionKey = "edition-narrator-b",
            narrator = "Диктор Б"
        )

        assertFalse(
            requiresNarrationSwitchConfirmation(
                current = current,
                target = target,
                approvedEditionKey = target.editionKey
            )
        )
    }

    @Test
    fun `search card with several asserted editions does not guess a narration`() {
        val result = GlobalSearchResult(
            title = "Проблема з миром",
            author = "Джон Доу",
            narrator = "Диктор Б",
            mergeKey = current.workKey,
            sources = listOf(
                GlobalSearchSource("4read", "4read", "https://one", "edition-a"),
                GlobalSearchSource("other", "other", "https://two", "edition-b")
            )
        )

        assertFalse(
            requiresNarrationSwitchConfirmation(current, narrationSwitchIdentity(result))
        )
    }

    @Test
    fun `book identity ignores source and local row id`() {
        val first = book("local-a", "https://4read.org/book")
        val second = book("local-b", "https://another.example/book")

        assertFalse(
            requiresNarrationSwitchConfirmation(
                narrationSwitchIdentity(first),
                narrationSwitchIdentity(second)
            )
        )
    }

    @Test
    fun `different known language is a different edition`() {
        val ukrainian = book("local-a", "https://4read.org/book").also { it.language = "uk" }
        val english = book("local-b", "https://another.example/book").also { it.language = "en" }

        assertTrue(
            requiresNarrationSwitchConfirmation(
                narrationSwitchIdentity(ukrainian),
                narrationSwitchIdentity(english)
            )
        )
    }

    @Test
    fun `identity carries the source name of the found narration`() {
        val owned = book("local-a", "https://sound-books.net/book")
        assertEquals("Sound-Books", narrationSwitchIdentity(owned).sourceName)

        // A merged card repeats the same source per URL — the prompt names it
        // once, never twice.
        val result = GlobalSearchResult(
            title = "Проблема з миром",
            author = "Джон Доу",
            narrator = "Диктор Б",
            mergeKey = current.workKey,
            sources = listOf(
                GlobalSearchSource("soundbooks", "Sound-Books", "https://sound-books.net/book", "edition-b"),
                GlobalSearchSource("soundbooks", "Sound-Books", "https://arch.sound-books.net/book", "edition-b")
            )
        )
        assertEquals("Sound-Books", narrationSwitchIdentity(result).sourceName)

        val catalog = CatalogBook(
            id = "b",
            title = "Проблема з миром",
            author = "Джон Доу",
            url = "https://librivox.org/book",
            coverImageUrl = null,
            narrator = "Диктор Б"
        )
        assertEquals("LibriVox", narrationSwitchIdentity(catalog).sourceName)
    }

    private val target = current.copy(
        editionKey = "edition-narrator-b",
        narrator = "Диктор Б",
        sourceName = "Sound-Books"
    )

    @Test
    fun `request defers the action and publishes a prompt with the source`() {
        val gate = NarrationSwitchGate()
        var ran = false

        val immediate = gate.request(current, target) { ran = true }

        assertFalse("nothing runs before confirmation", ran)
        assertFalse(immediate)
        val prompt = gate.prompt.value!!
        assertEquals("Диктор А", prompt.currentNarrator)
        assertEquals("Диктор Б", prompt.targetNarrator)
        assertEquals("Sound-Books", prompt.targetSourceName)
        assertEquals("edition-narrator-b", prompt.targetEditionKey)
    }

    @Test
    fun `confirm runs the deferred action exactly once`() {
        val gate = NarrationSwitchGate()
        var runs = 0
        gate.request(current, target) { runs++ }

        gate.confirm()
        gate.confirm()

        assertEquals(1, runs)
        assertNull(gate.prompt.value)
    }

    @Test
    fun `dismiss drops the deferred action and changes nothing`() {
        val gate = NarrationSwitchGate()
        var ran = false
        gate.request(current, target) { ran = true }

        gate.dismiss()

        assertFalse("refusal never starts the candidate", ran)
        assertNull(gate.prompt.value)
    }

    @Test
    fun `the same Edition runs immediately without a prompt`() {
        val gate = NarrationSwitchGate()
        var ran = false

        val immediate = gate.request(current, current.copy(title = "та сама начитка")) { ran = true }

        assertTrue(ran)
        assertTrue(immediate)
        assertNull(gate.prompt.value)
    }

    @Test
    fun `one approval covers a later request for the same target`() {
        val gate = NarrationSwitchGate()
        gate.request(current, target) {}
        gate.confirm()

        var ran = false
        val immediate = gate.request(current, target) { ran = true }

        assertTrue("the approved target never asks twice", ran)
        assertTrue(immediate)
    }

    private fun book(id: String, sourceUrl: String) = AudiobookEntity(
        id = id,
        title = "Проблема з миром",
        author = "Джон Доу",
        narrator = "Диктор А",
        description = "",
        coverDrawableRes = 0,
        genre = "",
        sourceUrl = sourceUrl
    ).also {
        it.mergeKey = current.workKey
    }
}
