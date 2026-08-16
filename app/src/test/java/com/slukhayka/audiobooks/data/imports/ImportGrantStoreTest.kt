package com.slukhayka.audiobooks.data.imports

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ImportGrantStoreTest {

    @Test
    fun `granted tree uris round-trip and dedupe`() {
        val store = ImportGrantStore(ApplicationProvider.getApplicationContext())

        assertTrue(store.grantedTreeUris().isEmpty())

        val tree = "content://com.android.externalstorage.documents/tree/primary%3AAudioBooks"
        store.addTreeUri(tree)
        store.addTreeUri(tree)

        assertEquals(setOf(tree), store.grantedTreeUris())
    }

    @Test
    fun `different trees accumulate`() {
        val store = ImportGrantStore(ApplicationProvider.getApplicationContext())

        store.addTreeUri("tree://one")
        store.addTreeUri("tree://two")

        assertEquals(setOf("tree://one", "tree://two"), store.grantedTreeUris())
    }
}