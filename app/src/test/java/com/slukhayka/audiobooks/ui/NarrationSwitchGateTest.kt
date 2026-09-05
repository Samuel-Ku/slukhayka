package com.slukhayka.audiobooks.ui

import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.source.GlobalSearchResult
import com.slukhayka.audiobooks.data.source.GlobalSearchSource
import org.junit.Assert.assertFalse
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
