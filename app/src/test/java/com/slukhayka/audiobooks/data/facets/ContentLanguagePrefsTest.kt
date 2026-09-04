package com.slukhayka.audiobooks.data.facets

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Spec-45 (#405) T6 (#494): the persisted Content Language preference —
 * default both on («Усі»), the state survives a store re-creation (US7), a
 * write is reflected on the live [ContentLanguagePrefs.languages] flow, and
 * "both off" / unknown languages are not states (the store normalizes back
 * to the default).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ContentLanguagePrefsTest {

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `default is both content languages on - the inactive Uusi state`() = runBlocking {
        val prefs = ContentLanguagePrefs(context())
        assertEquals(setOf("uk", "en"), prefs.languages.first())
    }

    @Test
    fun `state survives a store re-creation`() = runBlocking {
        val context = context()
        ContentLanguagePrefs(context).setLanguages(setOf("en"))

        val recreated = ContentLanguagePrefs(context)
        assertEquals(setOf("en"), recreated.languages.first())
        // And keeps flowing for the next write.
        recreated.setLanguages(setOf("uk", "en"))
        assertEquals(setOf("uk", "en"), recreated.languages.first())
    }

    @Test
    fun `write updates the live flow the surfaces read`() = runBlocking {
        val prefs = ContentLanguagePrefs(context())
        // A fresh collector sees the persisted value; every write is visible
        // on the flow synchronously — the feed Pager and the SourceCatalog
        // surfaces react to the same emissions.
        assertEquals(setOf("uk", "en"), prefs.languages.first())
        prefs.setLanguages(setOf("uk"))
        assertEquals(setOf("uk"), prefs.languages.first())
        prefs.setLanguages(setOf("en"))
        assertEquals(setOf("en"), prefs.languages.first())
    }

    @Test
    fun `both off and unknown languages normalize back to the default`() = runBlocking {
        val prefs = ContentLanguagePrefs(context())
        prefs.setLanguages(emptySet())
        assertEquals(setOf("uk", "en"), prefs.languages.first())

        prefs.setLanguages(setOf("fr", "uk"))
        assertEquals(setOf("uk"), prefs.languages.first())

        prefs.setLanguages(setOf("fr"))
        assertEquals(setOf("uk", "en"), prefs.languages.first())
    }
}
