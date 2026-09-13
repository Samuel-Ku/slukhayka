package com.slukhayka.audiobooks.data.collections

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Spec-51 (#689) — the SAME contract as [ListenerCollectionsStoreTest], but on
 * real Room: the on-disk store must behave exactly like the fake, because the
 * fake is what the rest of the feature is written against.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RoomListenerCollectionsStoreTest {

    private lateinit var db: AudiobookDatabase
    private lateinit var store: ListenerCollectionsStore
    private var now = 1_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = RoomListenerCollectionsStore(db.listenerCollectionsDao()) { now++ }
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `hygiene is applied on create and the row survives a read`() = runBlocking {
        val id = store.create("  Космос https://spam.example ", "Про зорі\n\nі людей")!!

        val saved = store.all().single { it.id == id }
        assertEquals("Космос", saved.title)
        assertEquals("Про зорі і людей", saved.description)
    }

    @Test
    fun `a link-only title cannot create a collection`() = runBlocking {
        assertNull(store.create("https://spam.example", null))
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `books keep insertion order and the first one is the cover`() = runBlocking {
        val id = store.create("Магія", null)!!
        assertTrue(store.add(id, "book-a", "бо атмосферно"))
        assertTrue(store.add(id, "book-b", null))

        val saved = store.all().single { it.id == id }
        assertEquals(listOf("book-a", "book-b"), saved.items.map { it.bookId })
        assertEquals("book-a", saved.coverBookId)
        assertEquals("бо атмосферно", saved.items.first().reason)
    }

    @Test
    fun `adding the same book twice is an honest no-op`() = runBlocking {
        val id = store.create("Магія", null)!!
        assertTrue(store.add(id, "book-a", null))
        assertFalse("already there", store.add(id, "book-a", null))
        assertEquals(1, store.all().single { it.id == id }.items.size)
    }

    @Test
    fun `removing works and unknown ids report false`() = runBlocking {
        val id = store.create("Магія", null)!!
        store.add(id, "book-a", null)

        assertTrue(store.remove(id, "book-a"))
        assertTrue(store.all().single { it.id == id }.items.isEmpty())
        assertFalse(store.remove(id, "book-a"))
        assertFalse(store.add("missing", "book-a", null))
    }

    @Test
    fun `renaming and deleting work on disk, and delete takes the items with it`() = runBlocking {
        val id = store.create("Стара", null)!!
        store.add(id, "book-a", null)

        assertTrue(store.rename(id, "Нова"))
        assertEquals("Нова", store.all().single { it.id == id }.title)
        assertFalse(store.rename(id, "https://spam.example"))

        assertTrue(store.delete(id))
        assertTrue(store.all().isEmpty())
        assertFalse(store.delete(id))
    }

    @Test
    fun `description edits are hygienic on disk`() = runBlocking {
        val id = store.create("Магія", "старий")!!
        assertTrue(store.updateDescription(id, " новий\n\nопис "))
        assertEquals("новий опис", store.all().single { it.id == id }.description)
    }
}
