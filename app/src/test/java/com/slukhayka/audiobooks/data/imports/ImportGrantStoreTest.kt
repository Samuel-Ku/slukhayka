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
    fun `folder choices survive recreation and updates without changing old or other grants`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = ImportGrantStore(context)
        store.addTreeUri("content://tree/legacy")
        val one = SourceRef.Folder("content://tree/one", "Кобзар", LocalFolderGrouping.ONE_BOOK)
        val other = SourceRef.Folder("content://tree/other", "Інша", LocalFolderGrouping.SEPARATE_BOOKS)
        store.addFolder(one)
        store.addFolder(other)
        val recreated = ImportGrantStore(context)
        assertEquals(one, recreated.folder(one.treeUri))
        assertEquals(SourceRef.Folder("content://tree/legacy"), recreated.folder("content://tree/legacy"))
        val changed = one.copy(displayName = "Новий Кобзар", grouping = LocalFolderGrouping.SEPARATE_BOOKS)
        recreated.addFolder(changed)
        val next = ImportGrantStore(context)
        assertEquals(changed, next.folder(one.treeUri))
        assertEquals(other, next.folder(other.treeUri))
        assertEquals(setOf(one.treeUri, other.treeUri, "content://tree/legacy"), next.grantedTreeUris())
    }

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