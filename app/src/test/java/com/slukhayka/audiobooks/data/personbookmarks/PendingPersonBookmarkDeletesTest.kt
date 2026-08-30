package com.slukhayka.audiobooks.data.personbookmarks

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PendingPersonBookmarkDeletesTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun clearQueue() {
        context.getSharedPreferences("person_bookmark_pending_deletes", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `delete queue survives restart and removes only the acknowledged key`() {
        val first = SharedPreferencesPendingPersonBookmarkDeletes(context)
        first.add("AUTHOR", "author-id")
        first.add("NARRATOR", "narrator-id")

        val restarted = SharedPreferencesPendingPersonBookmarkDeletes(context)
        assertTrue(restarted.keys().contains("AUTHOR" to "author-id"))
        restarted.remove("AUTHOR", "author-id")

        assertFalse(SharedPreferencesPendingPersonBookmarkDeletes(context).keys().contains("AUTHOR" to "author-id"))
        assertTrue(SharedPreferencesPendingPersonBookmarkDeletes(context).keys().contains("NARRATOR" to "narrator-id"))
    }
}
