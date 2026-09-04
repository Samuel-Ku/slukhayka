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
 * Spec-45 (#405) T8 (#496) — the one-time bilingual prompt (US9): fires only
 * after English books exist and the listener has not answered; each branch is
 * terminal and survives a store re-creation (restarts); no English → never
 * fires; an active language choice in ⚙️ already answers the question.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BilingualPromptEngineTest {

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `fires once English exists and the listener has not answered`() = runBlocking {
        val prefs = ContentLanguagePrefs(context())
        val engine = BilingualPromptEngine(prefs) { true }

        engine.evaluate()
        assertTrue("prompt must show after a sync that found English", engine.visible.value)
    }

    @Test
    fun `no English in the catalogue - never fires`() = runBlocking {
        val engine = BilingualPromptEngine(ContentLanguagePrefs(context())) { false }

        engine.evaluate()
        assertFalse("no English books - no prompt", engine.visible.value)

        // And the moment a later sync DOES write English, the prompt appears.
        val afterEnglish = BilingualPromptEngine(ContentLanguagePrefs(context())) { true }
        afterEnglish.evaluate()
        assertTrue("fires once the first en Edition exists", afterEnglish.visible.value)
    }

    @Test
    fun `keep English dismisses permanently - survives a store re-creation`() = runBlocking {
        val context = context()
        val engine = BilingualPromptEngine(ContentLanguagePrefs(context)) { true }
        engine.evaluate()
        assertTrue(engine.visible.value)

        engine.keepEnglish()
        assertFalse("prompt dismissed", engine.visible.value)
        // Both languages stay on («Усі») — «Залишити» changes nothing.
        assertEquals(setOf("uk", "en"), ContentLanguagePrefs(context).languages.value)

        // Restart: a fresh store + engine still never re-asks.
        val restarted = BilingualPromptEngine(ContentLanguagePrefs(context)) { true }
        restarted.evaluate()
        assertFalse("must never return after «Залишити»", restarted.visible.value)
    }

    @Test
    fun `Ukrainian only narrows the preference and dismisses permanently`() = runBlocking {
        val context = context()
        val engine = BilingualPromptEngine(ContentLanguagePrefs(context)) { true }
        engine.evaluate()
        assertTrue(engine.visible.value)

        engine.ukrainianOnly()
        assertFalse("prompt dismissed", engine.visible.value)
        assertEquals("preference narrowed to uk-only", setOf("uk"), ContentLanguagePrefs(context).languages.value)

        val restarted = BilingualPromptEngine(ContentLanguagePrefs(context)) { true }
        restarted.evaluate()
        assertFalse("must never return after «Лише українські»", restarted.visible.value)
    }

    @Test
    fun `an active language choice in settings already answers the question`() = runBlocking {
        val context = context()
        // The listener narrowed to uk-only in ⚙️ before any sync found English.
        val prefs = ContentLanguagePrefs(context)
        prefs.setLanguages(setOf("uk"))
        val engine = BilingualPromptEngine(prefs) { true }

        engine.evaluate()
        assertFalse("never ask over an active ⚙️ choice", engine.visible.value)
    }
}
