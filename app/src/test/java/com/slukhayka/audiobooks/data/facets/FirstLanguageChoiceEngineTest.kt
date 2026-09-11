package com.slukhayka.audiobooks.data.facets

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Spec-51 (#742) T2 — the one-time First Language Choice: fires only after
 * the first sync that wrote renditions (never before content exists), offers
 * exactly the languages that have it, and every branch is terminal across
 * restarts. An already-narrowed preference IS an answer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FirstLanguageChoiceEngineTest {

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `fires after the first sync that wrote renditions`() = runBlocking {
        val engine = FirstLanguageChoiceEngine(ContentLanguagePrefs(context())) {
            listOf("de", "uk", "en")
        }

        engine.evaluate()

        assertTrue(engine.visible.value)
        // The offer is what the catalogue really holds, in the repo order.
        assertEquals(listOf("uk", "en", "de"), engine.languages.value)
    }

    @Test
    fun `no content yet - never fires, and fires once it appears`() = runBlocking {
        val empty = FirstLanguageChoiceEngine(ContentLanguagePrefs(context())) { emptyList() }
        empty.evaluate()
        assertFalse("no renditions — nothing to ask about", empty.visible.value)

        val later = FirstLanguageChoiceEngine(ContentLanguagePrefs(context())) { listOf("en") }
        later.evaluate()
        assertTrue("fires once the first rendition exists", later.visible.value)
    }

    @Test
    fun `keepAll keeps Uusi and is terminal across restarts`() = runBlocking {
        val context = context()
        val engine = FirstLanguageChoiceEngine(ContentLanguagePrefs(context)) { listOf("uk", "en") }
        engine.evaluate()
        assertTrue(engine.visible.value)

        engine.keepAll()

        assertFalse("answered", engine.visible.value)
        assertTrue("every language stays on", ContentLanguagePrefs(context).languages.value.isEmpty())
        val restarted = FirstLanguageChoiceEngine(ContentLanguagePrefs(context)) { listOf("uk", "en") }
        restarted.evaluate()
        assertFalse("must never return after «Усі»", restarted.visible.value)
    }

    @Test
    fun `Ukrainian only narrows the preference and is terminal`() = runBlocking {
        val context = context()
        val engine = FirstLanguageChoiceEngine(ContentLanguagePrefs(context)) { listOf("uk", "en") }
        engine.evaluate()

        engine.ukrainianOnly()

        assertFalse(engine.visible.value)
        assertEquals(setOf("uk"), ContentLanguagePrefs(context).languages.value)
        val restarted = FirstLanguageChoiceEngine(ContentLanguagePrefs(context)) { listOf("uk", "en") }
        restarted.evaluate()
        assertFalse("must never return after «Лише українські»", restarted.visible.value)
    }

    @Test
    fun `an explicit selection is written and terminal`() = runBlocking {
        val context = context()
        val engine = FirstLanguageChoiceEngine(ContentLanguagePrefs(context)) { listOf("uk", "en", "de") }
        engine.evaluate()

        engine.apply(setOf("de", "fr"))

        assertEquals(setOf("de", "fr"), ContentLanguagePrefs(context).languages.value)
        assertFalse(engine.visible.value)
    }

    @Test
    fun `an already narrowed choice in settings answers the question`() = runBlocking {
        val prefs = ContentLanguagePrefs(context())
        prefs.setLanguages(setOf("uk"))
        val engine = FirstLanguageChoiceEngine(prefs) { listOf("uk", "de") }

        engine.evaluate()

        assertFalse("never ask over an active choice", engine.visible.value)
    }

    @Test
    fun `a language the normalizer cannot name is never offered`() = runBlocking {
        val engine = FirstLanguageChoiceEngine(ContentLanguagePrefs(context())) {
            listOf("uk", "Klingon", "")
        }

        engine.evaluate()

        assertEquals(listOf("uk"), engine.languages.value)
    }
}
