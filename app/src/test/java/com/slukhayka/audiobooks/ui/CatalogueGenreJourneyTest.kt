package com.slukhayka.audiobooks.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.GenreFacetOption
import com.slukhayka.audiobooks.data.source.GlobalSearchResult
import com.slukhayka.audiobooks.ui.screens.DiscoveryCollectionBook
import com.slukhayka.audiobooks.ui.screens.DiscoveryCollectionScreen
import com.slukhayka.audiobooks.ui.screens.bookDetailPresentation
import com.slukhayka.audiobooks.ui.screens.bookdetail.BookDetailCanonicalSummary
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "uk-rUA", sdk = [36])
class CatalogueGenreJourneyTest {
    @get:Rule val rule = createComposeRule()

    @Test fun `compact row leads to every canonical genre and its catalogue filter`() {
        val result = GlobalSearchResult(
            title = "Жанровий твір", author = "Автор", mergeKey = "genre-work", sources = emptyList(),
        )
        val book = AudiobookEntity(
            id = "genre-work", title = result.title, author = result.author, narrator = "",
            description = "", coverDrawableRes = 0, genre = "Фентезі", sourceUrl = "",
        )
        val facets = listOf(
            GenreFacetOption("fantasy", "Фентезі", 1),
            GenreFacetOption("adventure", "Пригоди", 1),
            GenreFacetOption("detective", "Детективи", 1),
        )
        var selectedGenre: String? = null
        rule.setContent {
            var detailOpen by remember { mutableStateOf(false) }
            AudiobookTheme(darkTheme = false) {
                if (detailOpen) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        BookDetailCanonicalSummary(
                            presentation = bookDetailPresentation(book, emptyList(), emptyList()),
                            genreFacets = facets,
                            onGenreClick = { selectedGenre = it },
                            requestInitialFocus = false,
                        )
                    }
                } else {
                    DiscoveryCollectionScreen(
                        title = "Добірка", books = listOf(DiscoveryCollectionBook(
                            result = result, genres = linkedSetOf("Фентезі", "Пригоди", "Детективи"),
                        )),
                        onBackClick = {}, onBookClick = { detailOpen = true }, onSearchAll = {},
                    )
                }
            }
        }
        rule.onNodeWithText("Фентезі · Пригоди +1").assertIsDisplayed()
        rule.onNodeWithText(result.title).performClick()
        rule.onNodeWithText("Фентезі").assertIsDisplayed()
        rule.onNodeWithText("Пригоди").assertIsDisplayed()
        rule.onNodeWithText("Детективи").performClick()
        assertEquals("detective", selectedGenre)
    }
}
