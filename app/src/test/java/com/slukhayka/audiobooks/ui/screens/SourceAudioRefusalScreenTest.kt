package com.slukhayka.audiobooks.ui.screens

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.metadata.SharedBookMetaStore
import com.slukhayka.audiobooks.data.source.SourceAudioRefusal
import com.slukhayka.audiobooks.testing.FakeSharedBookMetaStore
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Spec-49 T5 — the «Аудіо джерел» destination grows the voluntary publish
 * consent and the anonymous shared badge: the consent switch drives the
 * stored choice, the badge renders only known positive counts, refusing
 * without consent publishes nothing, refusing with consent counts exactly
 * once per device, enabling consent later catches up the already-refused
 * sources, and a down shared base never breaks the screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SourceAudioRefusalScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    private fun freshPrefs(): SourceAudioRefusal =
        SourceAudioRefusal(context()).also {
            it.setRefused(emptySet())
            it.setPublishRefusals(false)
        }

    private fun show(
        prefs: SourceAudioRefusal,
        store: SharedBookMetaStore?,
        uid: String? = "test-uid"
    ) {
        compose.setContent {
            AudiobookTheme {
                SourceAudioRefusalScreen(
                    prefs = prefs,
                    sharedStore = store,
                    uidProvider = { uid },
                    onBackClick = {}
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `consent switch drives the stored choice`() {
        runBlocking {
            val prefs = freshPrefs()
            show(prefs, FakeSharedBookMetaStore())

            compose.onNodeWithTag("source_audio_refusal_publish_checkbox").assertIsOff()
            compose.onNodeWithTag("source_audio_refusal_publish_checkbox").performClick()
            compose.onNodeWithTag("source_audio_refusal_publish_checkbox").assertIsOn()
            assertTrue(prefs.publishRefusals.first())

            compose.onNodeWithTag("source_audio_refusal_publish_checkbox").performClick()
            compose.onNodeWithTag("source_audio_refusal_publish_checkbox").assertIsOff()
        }
    }

    @Test
    fun `badge renders known counts and stays hidden otherwise`() {
        runBlocking {
            val store = FakeSharedBookMetaStore()
            store.publishRefusalVote("4read", "uid-1")
            store.publishRefusalVote("4read", "uid-2")
            show(freshPrefs(), store)

            compose.onNodeWithTag("source_audio_refusal_4read_shared_count").assertExists()
            compose.onNodeWithTag("source_audio_refusal_sluhayua_shared_count").assertDoesNotExist()
        }
    }

    @Test
    fun `refusing without consent publishes nothing`() {
        runBlocking {
            val store = FakeSharedBookMetaStore()
            show(freshPrefs(), store)

            compose.onNodeWithTag("source_audio_refusal_4read_checkbox").performClick()
            compose.waitForIdle()

            assertEquals(0L, store.getRefusalCount("4read"))
            compose.onNodeWithTag("source_audio_refusal_4read_shared_count").assertDoesNotExist()
        }
    }

    @Test
    fun `refusing with consent counts exactly once per device`() {
        runBlocking {
            val store = FakeSharedBookMetaStore()
            val prefs = freshPrefs()
            prefs.setPublishRefusals(true)
            show(prefs, store)

            compose.onNodeWithTag("source_audio_refusal_4read_checkbox").performClick()
            compose.waitForIdle()
            assertEquals(1L, store.getRefusalCount("4read"))
            compose.onNodeWithTag("source_audio_refusal_4read_shared_count").assertExists()

            // Allow and refuse again: the same device never counts twice.
            compose.onNodeWithTag("source_audio_refusal_4read_checkbox").performClick()
            compose.onNodeWithTag("source_audio_refusal_4read_checkbox").performClick()
            compose.waitForIdle()
            assertEquals(1L, store.getRefusalCount("4read"))
        }
    }

    @Test
    fun `enabling consent later publishes the already-refused sources`() {
        runBlocking {
            val store = FakeSharedBookMetaStore()
            val prefs = freshPrefs()
            prefs.refuse("lihtar")
            show(prefs, store)
            assertEquals(0L, store.getRefusalCount("lihtar"))

            compose.onNodeWithTag("source_audio_refusal_publish_checkbox").performClick()
            compose.waitForIdle()

            assertEquals(1L, store.getRefusalCount("lihtar"))
            compose.onNodeWithTag("source_audio_refusal_lihtar_shared_count").assertExists()
        }
    }

    @Test
    fun `a down shared base never breaks the screen`() {
        runBlocking {
            val store = FakeSharedBookMetaStore(refusalsDown = true)
            val prefs = freshPrefs()
            prefs.setPublishRefusals(true)
            show(prefs, store)

            compose.onNodeWithTag("source_audio_refusal_4read_checkbox").performClick()
            compose.waitForIdle()

            assertTrue("the local refusal stands on its own", prefs.isRefused("4read"))
            compose.onNodeWithTag("source_audio_refusal_4read_shared_count").assertDoesNotExist()
        }
    }

    @Test
    fun `a missing profile publishes nothing but keeps the local refusal`() {
        runBlocking {
            val store = FakeSharedBookMetaStore()
            val prefs = freshPrefs()
            prefs.setPublishRefusals(true)
            show(prefs, store, uid = null)

            compose.onNodeWithTag("source_audio_refusal_4read_checkbox").performClick()
            compose.waitForIdle()

            assertEquals(0L, store.getRefusalCount("4read"))
            assertTrue(prefs.isRefused("4read"))
        }
    }
}
