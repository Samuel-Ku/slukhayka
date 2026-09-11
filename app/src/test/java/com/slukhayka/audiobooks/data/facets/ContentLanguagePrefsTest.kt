package com.slukhayka.audiobooks.data.facets

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Spec-45 (#405) T6 (#494), spec-51 (#742) T1/T5 — the persisted Content
 * Language preference: «Усі» is the EMPTY selection (web parity), the accepted
 * vocabulary is the normalizer's (wider than uk/en), the legacy {uk, en}
 * default widens exactly once, and every write shows on the live flow the
 * surfaces read.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ContentLanguagePrefsTest {

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    /** Writes a value the way a pre-spec-51 install left it on disk. */
    private fun storeLegacy(context: Context, languages: Set<String>) {
        context.getSharedPreferences("content_language_prefs", Context.MODE_PRIVATE)
            .edit().putStringSet("content_languages", languages).commit()
    }

    @Test
    fun `default is Uusi - the empty selection`() = runBlocking {
        val prefs = ContentLanguagePrefs(context())
        assertTrue("nothing selected means every language", prefs.languages.first().isEmpty())
        assertTrue(prefs.isAll)
    }

    @Test
    fun `state survives a store re-creation`() = runBlocking {
        val context = context()
        ContentLanguagePrefs(context).setLanguages(setOf("de"))

        val recreated = ContentLanguagePrefs(context)
        assertEquals(setOf("de"), recreated.languages.first())
        assertFalse("a single language is a real filter, not «Усі»", recreated.isAll)
    }

    @Test
    fun `the vocabulary is the normalizer's - wider than uk and en`() = runBlocking {
        val prefs = ContentLanguagePrefs(context())
        // A language LibriVox really serves is a legal filter state now.
        prefs.setLanguages(setOf("de", "fr", "uk"))
        assertEquals(setOf("de", "fr", "uk"), prefs.languages.first())

        // A code the normalizer cannot produce is not a state: dropped, and
        // an all-dropped write lands on «Усі».
        prefs.setLanguages(setOf("xx"))
        assertTrue(prefs.languages.first().isEmpty())
    }

    @Test
    fun `the legacy uk plus en default widens to Uusi exactly once`() = runBlocking {
        val context = context()
        storeLegacy(context, setOf("uk", "en"))

        val migrated = ContentLanguagePrefs(context)
        assertTrue("the old «Усі» becomes the real «Усі»", migrated.languages.first().isEmpty())

        // Once migrated, a deliberate uk+en pick stays exactly that.
        migrated.setLanguages(setOf("uk", "en"))
        assertEquals(setOf("uk", "en"), ContentLanguagePrefs(context).languages.first())
    }

    @Test
    fun `a self-narrowed legacy selection is never touched`() = runBlocking {
        val context = context()
        storeLegacy(context, setOf("uk"))

        assertEquals(setOf("uk"), ContentLanguagePrefs(context).languages.first())
    }

    @Test
    fun `the answered marker is persisted and terminal`() = runBlocking {
        val context = context()
        assertFalse(ContentLanguagePrefs(context).answerRecorded)

        ContentLanguagePrefs(context).markAnswered()

        assertTrue(ContentLanguagePrefs(context).answerRecorded)
    }
}
