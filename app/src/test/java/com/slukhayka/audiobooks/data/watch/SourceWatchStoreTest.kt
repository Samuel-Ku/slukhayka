package com.slukhayka.audiobooks.data.watch

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
 * ADR-0037 §6 (spec-49 T4) — the persisted Source Watch store: empty by
 * default, the watch survives a store re-creation (the listener must not
 * re-arm it after every process death), unwatching also drops the
 * already-notified state so a re-watch can announce again, and blank
 * mergeKeys are never a state. The store is local-only by construction —
 * there is no sync path to test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SourceWatchStoreTest {

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `default is no watch`() = runBlocking {
        val store = SourceWatchStore(context())
        assertTrue(store.watched.first().isEmpty())
        assertFalse(store.isWatched("книга|автор"))
    }

    @Test
    fun `watch survives a store re-creation`() = runBlocking {
        val context = context()
        SourceWatchStore(context).watch("книга|автор", "work-1")

        val recreated = SourceWatchStore(context)
        assertEquals(mapOf("книга|автор" to "work-1"), recreated.watched.first())
        assertTrue(recreated.isWatched("книга|автор"))
    }

    @Test
    fun `re-watch re-arms the same work`() = runBlocking {
        val store = SourceWatchStore(context())
        store.watch("книга|автор", "work-1")
        store.watch("книга|автор", "work-9")

        assertEquals(mapOf("книга|автор" to "work-9"), store.watched.first())
    }

    @Test
    fun `auto-watch after a manual watch keeps one entry and the seen state`() = runBlocking {
        val store = SourceWatchStore(context())
        store.watch("книга|автор", "work-1")
        store.markSeen("книга|автор", setOf("soundbooks"))

        // The automatic T2 path goes through the same idempotent door.
        store.watch("книга|автор", "work-1")

        assertEquals(mapOf("книга|автор" to "work-1"), store.watched.first())
        assertEquals(setOf("soundbooks"), store.seenFor("книга|автор"))
    }

    @Test
    fun `unwatch drops the watch and the already-notified state`() = runBlocking {
        val context = context()
        val store = SourceWatchStore(context)
        store.watch("книга|автор", "work-1")
        store.markSeen("книга|автор", setOf("soundbooks"))

        store.unwatch("книга|автор")
        assertTrue(store.watched.first().isEmpty())
        assertTrue(store.seenFor("книга|автор").isEmpty())

        // A re-watch can announce again: the notification memory is gone.
        val recreated = SourceWatchStore(context)
        assertTrue(recreated.seenFor("книга|автор").isEmpty())
    }

    @Test
    fun `seen sources survive a store re-creation`() = runBlocking {
        val context = context()
        val store = SourceWatchStore(context)
        store.markSeen("книга|автор", setOf("soundbooks"))
        store.markSeen("книга|автор", setOf("sluhayua", "soundbooks"))

        val recreated = SourceWatchStore(context)
        assertEquals(setOf("soundbooks", "sluhayua"), recreated.seenFor("книга|автор"))
    }

    @Test
    fun `blank merge keys are never a state`() = runBlocking {
        val store = SourceWatchStore(context())
        store.watch("", "work-1")
        store.watch("  ", "work-1")
        store.markSeen("", setOf("soundbooks"))

        assertTrue(store.watched.first().isEmpty())
        assertTrue(store.seenFor("").isEmpty())
    }

    @Test
    fun `corrupted persisted json decodes to no watch`() = runBlocking {
        val context = context()
        context.getSharedPreferences("source_watch_prefs", Context.MODE_PRIVATE)
            .edit().putString("watched_merge_keys", "{not json").apply()

        val recreated = SourceWatchStore(context)
        assertTrue(recreated.watched.first().isEmpty())
    }
}
