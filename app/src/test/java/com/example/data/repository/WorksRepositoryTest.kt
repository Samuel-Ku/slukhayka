package com.example.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.catalog.SourceCatalog
import com.example.data.db.AudiobookDao
import com.example.data.db.AudiobookDatabase
import com.example.data.imports.LibraryImport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Repository seam (spec-23 T1): merge-on-write into the persisted Works /
 * Editions catalogue. The write path lives in the Source Catalog module and
 * reuses the validated [com.example.data.merge.MergeKey] normalization — no
 * new normalization, no read-time dedup. These tests pin the external
 * behaviour over in-memory Room: one Work per identity, one Edition per
 * source, idempotent re-writes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WorksRepositoryTest {

    private lateinit var context: Context
    private lateinit var db: AudiobookDatabase
    private lateinit var dao: AudiobookDao

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.audiobookDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ADR-0002 (#140): the catalog tests construct the Source Catalog module
    // directly — no god module.
    private fun catalog() = SourceCatalog(dao, emptyList(), LibraryImport(dao, context, emptyList()))

    @Test
    fun `same book from two sources yields one Work with two Editions`() = runBlocking {
        val catalog = catalog()

        val first = catalog.writeWorkEdition(
            sourceId = "sluhay",
            title = "Пасажир",
            author = "Жан-Крістоф Гранже",
            narrator = "",
            sourceUrl = "https://sluhay.com/pasazhir.html"
        )
        val second = catalog.writeWorkEdition(
            sourceId = "4read",
            title = "Пасажир",
            author = "Жан-Крістоф Гранже",
            narrator = "",
            sourceUrl = "https://4read.org/pasazhir.html"
        )

        // One Work — the merge key is reused for the id (the pinned identity).
        assertEquals(1, dao.countWorks())
        assertEquals(first.id, second.id)
        // The validated MergeKey normalization (punctuation stripped): the
        // hyphen in "Жан-Крістоф" is dropped, so the key is "жанкрістоф".
        assertEquals("пасажир|жанкрістоф гранже", first.mergeKey)
        // Two Editions — one per source, both pointing at the same Work.
        val editions = dao.getEditionsForWorkSync(first.id)
        assertEquals(2, editions.size)
        assertEquals(setOf("sluhay", "4read"), editions.map { it.sourceId }.toSet())
        assertEquals(setOf(first.id), editions.map { it.workId }.toSet())
    }

    @Test
    fun `re-writing the same source is idempotent - no third Edition no duplicate Work`() = runBlocking {
        val catalog = catalog()

        catalog.writeWorkEdition(
            sourceId = "sluhay",
            title = "Пасажир",
            author = "Жан-Крістоф Гранже",
            narrator = "",
            sourceUrl = "https://sluhay.com/pasazhir.html"
        )
        catalog.writeWorkEdition(
            sourceId = "4read",
            title = "Пасажир",
            author = "Жан-Крістоф Гранже",
            narrator = "",
            sourceUrl = "https://4read.org/pasazhir.html"
        )
        // Re-hydration run: both sources re-written with the same URLs.
        catalog.writeWorkEdition(
            sourceId = "sluhay",
            title = "Пасажир",
            author = "Жан-Крістоф Гранже",
            narrator = "",
            sourceUrl = "https://sluhay.com/pasazhir.html"
        )
        catalog.writeWorkEdition(
            sourceId = "4read",
            title = "Пасажир",
            author = "Жан-Крістоф Гранже",
            narrator = "",
            sourceUrl = "https://4read.org/pasazhir.html"
        )

        assertEquals(1, dao.countWorks())
        assertEquals(2, dao.countEditions())
    }

    @Test
    fun `books without identity never merge - blank key rows stay separate Works`() = runBlocking {
        val catalog = catalog()

        // No author on either side → MergeKey is blank → no identity to merge.
        catalog.writeWorkEdition(
            sourceId = "sluhay",
            title = "Аудіовистава",
            author = "",
            narrator = "",
            sourceUrl = "https://sluhay.com/audioplay-1.html"
        )
        catalog.writeWorkEdition(
            sourceId = "sluhay",
            title = "Інша аудіовистава",
            author = "",
            narrator = "",
            sourceUrl = "https://sluhay.com/audioplay-2.html"
        )

        assertEquals(2, dao.countWorks())
        assertEquals(2, dao.countEditions())
        // The stable per-source ids differ — never a blank-key collision.
        val works = dao.observeWorks().first()
        assertNotEquals(works[0].id, works[1].id)
    }

    @Test
    fun `different narrators stay separate Works`() = runBlocking {
        val catalog = catalog()

        catalog.writeWorkEdition(
            sourceId = "4read",
            title = "Кобзар",
            author = "Тарас Шевченко",
            narrator = "Валерій Завалко",
            sourceUrl = "https://4read.org/kobzar-1.html"
        )
        catalog.writeWorkEdition(
            sourceId = "sluhay",
            title = "Кобзар",
            author = "Тарас Шевченко",
            narrator = "Інший читач",
            sourceUrl = "https://sluhay.com/kobzar-2.html"
        )

        // Narrator is part of the merge key when known (ADR-0001: incompatible
        // narrations must not share a card) — two distinct Works.
        assertEquals(2, dao.countWorks())
        val works = dao.observeWorks().first()
        // Each distinct Work carries exactly its own Edition.
        assertEquals(setOf(1), works.map { dao.getEditionsForWorkSync(it.id).size }.toSet())
    }
}
