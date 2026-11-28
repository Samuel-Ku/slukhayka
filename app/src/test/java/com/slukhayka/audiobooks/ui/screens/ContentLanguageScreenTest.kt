package com.slukhayka.audiobooks.ui.screens

import android.content.Context
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.facets.ContentLanguagePrefs
import com.slukhayka.audiobooks.testing.assertNamedCheckboxRow
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1809 — «Мови контенту» had no UI test at all: the checkbox was the only
 * interactive node and the language name was a dead neighbour, so TalkBack
 * announced «прапорець, не позначено» with no language. Every row — including
 * «Усі» — is now one named checkbox whose label is a live target.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "uk-rUA")
class ContentLanguageScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun prefs(): ContentLanguagePrefs =
        ContentLanguagePrefs(context).also { it.setLanguages(emptySet()) }

    private fun show(prefs: ContentLanguagePrefs) {
        compose.setContent {
            AudiobookTheme {
                ContentLanguageScreen(
                    prefs = prefs,
                    availableLanguages = listOf("uk", "en"),
                    onBackClick = {}
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `the all row is one named checkbox that reads its state`() = runBlocking<Unit> {
        val prefs = prefs()
        show(prefs)

        compose.assertNamedCheckboxRow(
            "content_language_all_checkbox",
            context.getString(R.string.content_language_all)
        )
        // Nothing picked is «Усі», so the row starts ticked.
        compose.onNodeWithTag("content_language_all_checkbox").assertIsOn()
        compose.onNodeWithTag("content_language_en_checkbox").assertIsOff()
    }

    @Test
    fun `a language row is named and tapping its label selects it`() = runBlocking<Unit> {
        val prefs = prefs()
        show(prefs)

        val english = context.getString(R.string.content_language_en)
        compose.assertNamedCheckboxRow("content_language_en_checkbox", english)

        compose.onNodeWithText(english).performClick()
        compose.waitForIdle()

        assertEquals(setOf("en"), prefs.languages.first())
        compose.onNodeWithTag("content_language_en_checkbox").assertIsOn()
        compose.onNodeWithTag("content_language_all_checkbox").assertIsOff()
    }

    @Test
    fun `tapping the all label returns to every language and then stops`() = runBlocking<Unit> {
        val prefs = prefs()
        prefs.setLanguages(setOf("en"))
        show(prefs)

        val all = context.getString(R.string.content_language_all)

        // «Усі» is off while a subset is picked: tapping the label turns every
        // language back on (the empty set IS «Усі»).
        compose.onNodeWithText(all).performClick()
        compose.waitForIdle()
        assertEquals(emptySet<String>(), prefs.languages.first())
        compose.onNodeWithTag("content_language_all_checkbox").assertIsOn()

        // Unpicking an already-on «Усі» is the documented no-op: an empty
        // selection would mean «Усі» anyway, so nothing may change.
        compose.onNodeWithText(all).performClick()
        compose.waitForIdle()
        assertEquals(emptySet<String>(), prefs.languages.first())
    }
}
