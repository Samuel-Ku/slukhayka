package com.slukhayka.audiobooks.data.source

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
 * ADR-0037 (spec-49 T1) — the persisted Source Audio Refusal: empty by
 * default, the state survives a store re-creation (the dormant rows wake
 * with no re-import on undo), writes land on the live flow the
 * coordinator/catalog/download consumers read, and the `local`
 * pseudo-source is never a state (the refusal stops a source supplying
 * audio, never the listener's own files).
 *
 * Spec-49 T5 — the separate voluntary publish consent: off by default,
 * persisted beside the refusal, revoking it stops contributions while the
 * local refusal itself stands untouched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SourceAudioRefusalTest {

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `default is no refusal`() = runBlocking {
        val prefs = SourceAudioRefusal(context())
        prefs.setRefused(emptySet())
        assertTrue(prefs.refusedSources.first().isEmpty())
        assertFalse(prefs.isRefused("4read"))
    }

    @Test
    fun `state survives a store re-creation`() = runBlocking {
        val context = context()
        SourceAudioRefusal(context).refuse("4read")

        val recreated = SourceAudioRefusal(context)
        assertEquals(setOf("4read"), recreated.refusedSources.first())

        // Undoing the refusal wakes the dormant sources with no re-import.
        recreated.allow("4read")
        assertTrue(recreated.refusedSources.first().isEmpty())
    }

    @Test
    fun `write updates the live flow the consumers read`() = runBlocking {
        val prefs = SourceAudioRefusal(context())
        prefs.setRefused(emptySet())
        prefs.refuse("4read")
        assertEquals(setOf("4read"), prefs.refusedSources.first())
        assertTrue(prefs.isRefused("4read"))

        prefs.refuse("sluhayua")
        assertEquals(setOf("4read", "sluhayua"), prefs.refusedSources.first())

        prefs.allow("4read")
        assertEquals(setOf("sluhayua"), prefs.refusedSources.first())
        prefs.setRefused(emptySet())
    }

    @Test
    fun `the local pseudo-source is never a refusal state`() = runBlocking {
        val prefs = SourceAudioRefusal(context())
        prefs.setRefused(emptySet())
        prefs.refuse("local")
        prefs.refuse("4read")
        assertEquals("the listener's own files are never refused", setOf("4read"), prefs.refusedSources.first())
        assertFalse(prefs.isRefused("local"))

        prefs.setRefused(setOf("local", "", "4read"))
        assertEquals(setOf("4read"), prefs.refusedSources.first())
        prefs.setRefused(emptySet())
    }

    @Test
    fun `a blank source id is never refused`() = runBlocking {
        val prefs = SourceAudioRefusal(context())
        prefs.setRefused(emptySet())
        assertFalse(prefs.isRefused(""))
    }

    @Test
    fun `publish consent defaults to off`() = runBlocking {
        val prefs = SourceAudioRefusal(context())
        prefs.setPublishRefusals(false)
        assertFalse(prefs.publishRefusals.first())
    }

    @Test
    fun `publish consent survives a store re-creation`() = runBlocking {
        val context = context()
        SourceAudioRefusal(context).setPublishRefusals(true)
        assertTrue(SourceAudioRefusal(context).publishRefusals.first())

        SourceAudioRefusal(context).setPublishRefusals(false)
        assertFalse(SourceAudioRefusal(context).publishRefusals.first())
    }

    @Test
    fun `revoking consent stops contributions but the local refusal stands`() = runBlocking {
        val prefs = SourceAudioRefusal(context())
        prefs.setRefused(emptySet())
        prefs.setPublishRefusals(true)
        prefs.refuse("4read")

        prefs.setPublishRefusals(false)

        assertFalse(prefs.publishRefusals.first())
        assertTrue("the local refusal is untouched by the consent switch", prefs.isRefused("4read"))
        prefs.setRefused(emptySet())
    }
}
