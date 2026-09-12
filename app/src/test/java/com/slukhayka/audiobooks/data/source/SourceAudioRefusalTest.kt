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
 * ADR-0037 (spec-49 T1) — the persisted Source Audio Refusal: the built-in
 * scam refusal plus the listener's choices, the state survives a store
 * re-creation (the dormant rows wake with no re-import on undo), writes land
 * on the live flow the coordinator/catalog/download consumers read, and the
 * `local` pseudo-source is never a state (the refusal stops a source
 * supplying audio, never the listener's own files).
 *
 * A scam source (4read: its clean-client stream is a 52-second artefact) is
 * ALWAYS refused — no listener action can allow it.
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
    fun `the default is the built-in scam refusal only`() = runBlocking {
        val prefs = SourceAudioRefusal(context())
        prefs.setRefused(emptySet())
        assertEquals(SourceAudioRefusal.ALWAYS_REFUSED, prefs.refusedSources.first())
        assertTrue(prefs.isRefused("4read"))
        assertFalse(prefs.isRefused("soundbooks"))
    }

    @Test
    fun `state survives a store re-creation`() = runBlocking {
        val context = context()
        SourceAudioRefusal(context).refuse("soundbooks")

        val recreated = SourceAudioRefusal(context)
        assertEquals(setOf("4read", "soundbooks"), recreated.refusedSources.first())

        // Undoing the refusal wakes the dormant sources with no re-import.
        recreated.allow("soundbooks")
        assertEquals(SourceAudioRefusal.ALWAYS_REFUSED, recreated.refusedSources.first())
    }

    @Test
    fun `write updates the live flow the consumers read`() = runBlocking {
        val prefs = SourceAudioRefusal(context())
        prefs.setRefused(emptySet())
        prefs.refuse("soundbooks")
        assertEquals(setOf("4read", "soundbooks"), prefs.refusedSources.first())
        assertTrue(prefs.isRefused("soundbooks"))

        prefs.refuse("sluhayua")
        assertEquals(setOf("4read", "soundbooks", "sluhayua"), prefs.refusedSources.first())

        prefs.allow("soundbooks")
        assertEquals(setOf("4read", "sluhayua"), prefs.refusedSources.first())
        prefs.setRefused(emptySet())
    }

    @Test
    fun `the scam source can never be allowed`() = runBlocking {
        val prefs = SourceAudioRefusal(context())
        prefs.allow("4read")
        prefs.setRefused(emptySet())
        assertTrue("the built-in scam refusal rides every write", prefs.isRefused("4read"))
        assertEquals(SourceAudioRefusal.ALWAYS_REFUSED, prefs.refusedSources.first())
    }

    @Test
    fun `the local pseudo-source is never a refusal state`() = runBlocking {
        val prefs = SourceAudioRefusal(context())
        prefs.setRefused(emptySet())
        prefs.refuse("local")
        prefs.refuse("soundbooks")
        assertEquals(
            "the listener's own files are never refused",
            setOf("4read", "soundbooks"),
            prefs.refusedSources.first()
        )
        assertFalse(prefs.isRefused("local"))

        prefs.setRefused(setOf("local", "", "soundbooks"))
        assertEquals(setOf("4read", "soundbooks"), prefs.refusedSources.first())
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
        prefs.refuse("soundbooks")

        prefs.setPublishRefusals(false)

        assertFalse(prefs.publishRefusals.first())
        assertTrue("the local refusal is untouched by the consent switch", prefs.isRefused("soundbooks"))
        prefs.setRefused(emptySet())
    }
}
