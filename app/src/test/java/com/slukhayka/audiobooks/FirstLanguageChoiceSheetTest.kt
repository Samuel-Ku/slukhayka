package com.slukhayka.audiobooks

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.slukhayka.audiobooks.ui.screens.FirstLanguageChoiceContent
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Spec-51 (#742) T2 — the one-time First Language Choice: every language with
 * content is offered (checked by default), and both actions answer terminally.
 * The extracted [FirstLanguageChoiceContent] is the seam — no ModalBottomSheet
 * window is hosted (the LibraryFilterSheet pattern).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FirstLanguageChoiceSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private fun setContent(
        languages: List<String> = listOf("uk", "en", "de"),
        onUkrainianOnly: () -> Unit = {},
        onDone: (Set<String>) -> Unit = {}
    ) {
        compose.setContent {
            AudiobookTheme {
                FirstLanguageChoiceContent(
                    languages = languages,
                    onUkrainianOnly = onUkrainianOnly,
                    onDone = onDone
                )
            }
        }
    }

    @Test
    fun `asks the question, lists every content language and both actions`() {
        setContent()

        compose.onNodeWithText("Якими мовами показувати книжки?").assertExists()
        compose.onNodeWithTag("first_language_choice_uk").assertExists()
        compose.onNodeWithTag("first_language_choice_en").assertExists()
        compose.onNodeWithTag("first_language_choice_de").assertExists()
        compose.onNodeWithText("Лише українські").assertExists()
        compose.onNodeWithText("Готово").assertExists()
    }

    @Test
    fun `Ukrainian only action is wired`() {
        var ukOnly = false
        setContent(onUkrainianOnly = { ukOnly = true })

        compose.onNodeWithText("Лише українські").performClick()

        assertTrue(ukOnly)
    }

    @Test
    fun `everything left on means Uusi - the empty selection`() {
        var done: Set<String>? = null
        setContent(onDone = { done = it })

        compose.onNodeWithText("Готово").performClick()

        // «Усі» is carried as the empty selection, so a language that gains
        // content later appears without asking again.
        assertEquals(emptySet<String>(), done)
    }

    @Test
    fun `unchecking the other languages narrows to the one left`() {
        var done: Set<String>? = null
        setContent(languages = listOf("uk", "en", "de"), onDone = { done = it })

        compose.onNodeWithTag("first_language_choice_uk").performClick()
        compose.onNodeWithTag("first_language_choice_en").performClick()
        compose.onNodeWithText("Готово").performClick()

        assertEquals(setOf("de"), done)
    }
}
