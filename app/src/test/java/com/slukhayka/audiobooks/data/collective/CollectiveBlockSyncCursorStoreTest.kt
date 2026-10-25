package com.slukhayka.audiobooks.data.collective

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1238 — the shared delta cursor is durable and server-ordered: it survives a
 * restart of the store object, a pre-#1238 cursor written with the observation
 * time is NOT mistaken for a revision, and saving removes the stale field.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CollectiveBlockSyncCursorStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `the revision cursor survives a restart`() {
        val cursor = CollectiveBlockCursor(revision = 42L, documentId = "doc-42")
        SharedPreferencesCollectiveBlockSyncCursorStore(context).save(cursor)

        val afterRestart = SharedPreferencesCollectiveBlockSyncCursorStore(context)

        assertEquals(cursor, afterRestart.load())
    }

    @Test
    fun `a pre-1238 fetchedAt cursor is absent, not guessed`() {
        context.getSharedPreferences(
            SharedPreferencesCollectiveBlockSyncCursorStore.PREFS_NAME,
            Context.MODE_PRIVATE
        ).edit()
            .putLong("fetched_at", 1_700_000_000_000L)
            .putString("document_id", "old-doc")
            .commit()

        assertNull(SharedPreferencesCollectiveBlockSyncCursorStore(context).load())
    }

    @Test
    fun `saving the revision cursor removes the legacy fetched_at field`() {
        val prefs = context.getSharedPreferences(
            SharedPreferencesCollectiveBlockSyncCursorStore.PREFS_NAME,
            Context.MODE_PRIVATE
        )
        prefs.edit().putLong("fetched_at", 1L).commit()

        SharedPreferencesCollectiveBlockSyncCursorStore(context)
            .save(CollectiveBlockCursor(revision = 3L, documentId = "d3"))

        assertTrue(!prefs.contains("fetched_at"))
        assertEquals(3L, prefs.getLong("revision", -1L))
    }
}
