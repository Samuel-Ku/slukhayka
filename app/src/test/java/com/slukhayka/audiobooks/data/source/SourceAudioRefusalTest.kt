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
    fun `4read audio is always refused - the 52 second scam never plays`() = runBlocking {
        val prefs = SourceAudioRefusal(context())
        // A fresh store already refuses 4read.
        assertTrue(prefs.isRefused("4read"))
        assertEquals(setOf("4read"), prefs.refusedSources.first())
        // Neither an explicit allow nor an empty write can re-enable it: the
        // source's 52-second artefact is never audio, so the refusal is not a
        // listener toggle.
        prefs.allow("4read")
        assertTrue(prefs.isRefused("4read"))
        prefs.setRefused(emptySet())
        assertTrue(prefs.isRefused("4read"))
        assertEquals(setOf("4read"), prefs.refusedSources.first())
    }

    @Test
    fun `state survives a store re-creation and 4read stays refused`() = runBlocking {
        val context = context()
        SourceAudioRefusal(context).refuse("sluhayua")

        val recreated = SourceAudioRefusal(context)
        assertEquals(setOf("4read", "sluhayua"), recreated.refusedSources.first())

        // Undoing a listener-chosen refusal wakes the dormant source with no
        // re-import; the built-in 4read refusal stays.
        recreated.allow("sluhayua")
        assertEquals(setOf("4read"), recreated.refusedSources.first())
    }

    @Test
    fun `write updates the live flow the consumers read`() = runBlocking {
        val prefs = SourceAudioRefusal(context())
        prefs.setRefused(emptySet())
        prefs.refuse("sluhayua")
        assertEquals(setOf("4read", "sluhayua"), prefs.refusedSources.first())
        assertTrue(prefs.isRefused("sluhayua"))

        prefs.refuse("sluhayua")
        assertEquals(setOf("4read", "sluhayua"), prefs.refusedSources.first())

        prefs.allow("4read")
        assertEquals(setOf("4read", "sluhayua"), prefs.refusedSources.first())
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
