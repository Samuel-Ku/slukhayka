package com.slukhayka.audiobooks.data.personbookmarks

import android.content.Context
import androidx.room.Room
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.PersonBookmarkEntity
import com.slukhayka.audiobooks.data.db.PersonRole
import com.slukhayka.audiobooks.data.identity.FakeListenerIdentity
import com.slukhayka.audiobooks.data.identity.ListenerIdentity
import com.slukhayka.audiobooks.data.identity.ListenerProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #404 — Room local truth plus a Firebase-free wire fake. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PersonBookmarksSyncControllerTest {
    private lateinit var database: AudiobookDatabase
    private lateinit var bookmarks: PersonBookmarks

    private class FakeStore : PersonBookmarksSyncStore {
        val remote = linkedMapOf<String, Map<String, Any>>()
        val writes = mutableListOf<Pair<String, Map<String, Any>>>()
        val deletes = mutableListOf<String>()

        override suspend fun fetch(uid: String) = remote.values.toList()

        override suspend fun write(documentId: String, fields: Map<String, Any>): Boolean {
            writes += documentId to fields
            return true
        }

        override suspend fun delete(documentId: String): Boolean {
            deletes += documentId
            return true
        }
    }

    private class LocalOnlyIdentity : ListenerIdentity {
        override suspend fun ensure() = ListenerProfile("local-offline", "Слухач")
        override suspend fun current(): ListenerProfile? = null
        override suspend fun setNickname(nickname: String) = Unit
        override suspend fun recoveryCode(): String? = null
        override suspend fun restoreFromCode(code: String): ListenerProfile? = null
    }

    private class PendingDeletes : PendingPersonBookmarkDeletes {
        private val values = linkedSetOf<Pair<String, String>>()
        override fun keys(): Set<Pair<String, String>> = values.toSet()
        override fun add(kind: String, personId: String) { values += kind to personId }
        override fun remove(kind: String, personId: String) { values -= kind to personId }
    }

    @Before
    fun setUp() {
        val context: Context = androidx.test.core.app.ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries().build()
        bookmarks = PersonBookmarks(database.audiobookDao(), Dispatchers.Unconfined)
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `newer remote bookmark wins the LWW merge including notification choice`() = runBlocking {
        val identity = FakeListenerIdentity(kotlin.random.Random(1))
        val store = FakeStore()
        val person = bookmarks.identity(PersonRole.AUTHOR, "Леся Українка")
        bookmarks.toggle(person, nowMs = 100L)
        val uid = identity.ensure().uid
        store.remote[PersonBookmarksSyncCodec.documentId(uid, person.role.storageValue, person.id)] = mapOf(
            "uid" to uid, "kind" to person.role.storageValue, "personId" to person.id,
            "displayName" to "Леся Українка", "notifyEnabled" to false, "updatedAt" to 200L
        )

        PersonBookmarksSyncController(bookmarks, identity, store).sync()

        val merged = bookmarks.allBookmarks().first().single()
        assertFalse(merged.notifyEnabled)
        assertEquals(200L, merged.updatedAt)
        assertTrue(store.writes.isEmpty())
    }

    @Test
    fun `local bookmark is pushed and explicit removal becomes a remote delete`() = runBlocking {
        val identity = FakeListenerIdentity(kotlin.random.Random(2))
        val store = FakeStore()
        val person = bookmarks.identity(PersonRole.NARRATOR, "Петро Бойко")
        bookmarks.toggle(person, nowMs = 100L)
        val controller = PersonBookmarksSyncController(bookmarks, identity, store)

        controller.sync()
        controller.remove(person.role.storageValue, person.id)

        val uid = identity.ensure().uid
        val documentId = PersonBookmarksSyncCodec.documentId(uid, person.role.storageValue, person.id)
        assertEquals(documentId, store.writes.single().first)
        assertEquals(true, store.writes.single().second["notifyEnabled"])
        assertEquals(listOf(documentId), store.deletes)
    }

    @Test
    fun `local-only identity leaves its bookmarks and network untouched`() = runBlocking {
        val store = FakeStore()
        bookmarks.toggleAuthor("Іван Франко", nowMs = 100L)

        PersonBookmarksSyncController(bookmarks, LocalOnlyIdentity(), store).sync()

        assertTrue(store.writes.isEmpty())
        assertTrue(store.deletes.isEmpty())
        assertEquals(1, bookmarks.allBookmarks().first().size)
    }

    @Test
    fun `offline explicit delete is queued and cannot resurrect from remote`() = runBlocking {
        val identity = FakeListenerIdentity(kotlin.random.Random(3))
        val store = FakeStore()
        val pending = PendingDeletes()
        val person = bookmarks.identity(PersonRole.AUTHOR, "Ольга Кобилянська")
        val uid = identity.ensure().uid
        val documentId = PersonBookmarksSyncCodec.documentId(uid, person.role.storageValue, person.id)
        store.remote[documentId] = mapOf(
            "uid" to uid, "kind" to person.role.storageValue, "personId" to person.id,
            "displayName" to person.displayName, "notifyEnabled" to true, "updatedAt" to 200L
        )
        val controller = PersonBookmarksSyncController(bookmarks, identity, store, pending)
        // Simulate unavailable Firestore for the initial explicit removal.
        val offlineStore = object : PersonBookmarksSyncStore by store {
            override suspend fun remove(uid: String, kind: String, personId: String) = false
        }
        val offlineController = PersonBookmarksSyncController(bookmarks, identity, offlineStore, pending)

        // The local source of truth removes the bookmark before its best-effort
        // cloud mutation is queued. A stale remote response must not add it
        // back while that removal is still pending.
        assertTrue(bookmarks.toggle(person, nowMs = 100L))
        assertFalse(bookmarks.toggle(person, nowMs = 101L))
        assertEquals(null, database.audiobookDao().getPersonBookmark(person.role.storageValue, person.id))
        offlineController.remove(person.role.storageValue, person.id)
        assertTrue(pending.keys().contains(person.role.storageValue to person.id))
        controller.sync()

        assertEquals(null, database.audiobookDao().getPersonBookmark(person.role.storageValue, person.id))
        assertTrue(store.deletes.contains(documentId))
        assertTrue(pending.keys().isEmpty())
    }
}
