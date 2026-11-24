package com.slukhayka.audiobooks.data.catalog

import com.slukhayka.audiobooks.data.collections.CollectionEntry
import com.slukhayka.audiobooks.data.collections.CollectionList
import com.slukhayka.audiobooks.data.imports.LibraryImport
import com.slukhayka.audiobooks.testing.FakeAudiobookDao
import com.slukhayka.audiobooks.testing.TestDataFactory
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * #1730 — the startup batch re-matches the shelves after publishing the
 * curated lists (`SourceCatalog.refreshCuratedCollections`), while the first
 * `refreshUnifiedCatalog` may still be in flight on another dispatcher. The
 * newer list must not be lost to the older in-flight match: both the list
 * snapshot and the published result are one critical section.
 */
class SourceCatalogCuratedPublicationTest {

    private val book = TestDataFactory.dataBooks().first().copy(
        id = "kobzar",
        title = "Кобзар",
        author = "Тарас Шевченко"
    )

    @Test
    fun `a re-match cannot be overwritten by an in-flight older curated publication`() {
        val dao = FakeAudiobookDao(books = listOf(book))
        runBlocking {
            dao.upsertLibraryEntry(
                id = book.id,
                workId = book.id,
                isFavorite = false,
                createdAt = 1L,
                downloadProgress = 0f,
                downloadState = "IDLE",
                preserveExistingDownloads = false,
                origin = "EXPLICIT_SAVE"
            )
        }

        val staleList = CollectionList(
            id = "stale",
            name = "Старі",
            entries = listOf(CollectionEntry(author = book.author, title = book.title))
        )
        val freshList = staleList.copy(id = "fresh", name = "Нові")

        val orchestration = AtomicBoolean(false)
        val staleEntered = CountDownLatch(1)
        val releaseStale = CountDownLatch(1)
        val staleThread = AtomicReference<Thread?>(null)

        val catalog = SourceCatalog(
            dao = dao,
            sourceAdapters = emptyList(),
            libraryImport = LibraryImport(dao, null, emptyList()),
            collectionListsProvider = {
                when {
                    // The corpus pre-fill below must not consume the stale call.
                    !orchestration.get() -> emptyList()
                    Thread.currentThread() === staleThread.get() -> {
                        staleEntered.countDown()
                        releaseStale.await(5, TimeUnit.SECONDS)
                        listOf(staleList)
                    }
                    else -> listOf(freshList)
                }
            },
            // The live collector must not add a third publication to this
            // race; this test drives the two publications explicitly.
            contentLanguageSelection = SilentStateFlow(emptySet())
        )
        // Fill collectionsMatchCorpus the way the first refresh does, without
        // the startup batch's newer curated list yet.
        runBlocking { catalog.refreshUnifiedCatalog() }
        assertEquals(emptyList<String>(), catalog.smartCollections.value.map { it.id })

        orchestration.set(true)
        val stalePublish = Thread { catalog.refreshCuratedCollections() }
        staleThread.set(stalePublish)
        stalePublish.start()
        assertTrue(
            "the older re-match must reach the curated-list provider",
            staleEntered.await(5, TimeUnit.SECONDS)
        )

        val freshPublished = CountDownLatch(1)
        val freshPublish = Thread {
            catalog.refreshCuratedCollections()
            freshPublished.countDown()
        }
        freshPublish.start()

        // On the unlocked code the fresh publication finishes here and the
        // older one then overwrites it; with the publication lock the fresh
        // one waits for the older one and publishes last.
        freshPublished.await(2, TimeUnit.SECONDS)
        releaseStale.countDown()
        stalePublish.join(5_000)
        freshPublish.join(5_000)

        assertEquals(
            "the startup batch's newer curated list must win",
            listOf("fresh"),
            catalog.smartCollections.value.map { it.id }
        )
    }

    private class SilentStateFlow<T>(override val value: T) : StateFlow<T> {
        override val replayCache: List<T> = listOf(value)

        override suspend fun collect(collector: FlowCollector<T>): Nothing = awaitCancellation()
    }
}
