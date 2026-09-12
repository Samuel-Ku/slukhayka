package com.slukhayka.audiobooks.data.collective

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.catalog.SourceCatalog
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.imports.LibraryImport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #522 — mirroring a collective card: an accepted card lands in the local
 * Work/Edition/Source rows through the ordinary merge-on-write seam, and
 * re-applying it (a re-read delta) changes nothing; a rejected card lands
 * nothing at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CollectiveCardApplyRoomTest {

    private lateinit var context: Context
    private lateinit var db: AudiobookDatabase
    private lateinit var dao: AudiobookDao
    private lateinit var catalog: SourceCatalog

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.audiobookDao()
        catalog = SourceCatalog(dao, emptyList(), LibraryImport(dao, context, emptyList()))
    }

    @After
    fun tearDown() = db.close()

    private fun card(
        sourceId: String = "soundbooks",
        sourceUrl: String = "https://sound-books.net/kobzar",
        title: String = "Кобзар",
        author: String = "Тарас Шевченко"
    ) = CollectiveCardPublication(
        sourceId = sourceId,
        sourceUrl = sourceUrl,
        title = title,
        author = author,
        narrator = "Диктор",
        language = "uk",
        coverUrl = "https://sound-books.net/covers/kobzar.jpg",
        durationSeconds = 7_200L,
        observedAt = 1_700_000_000_000L
    )

    @Test
    fun `an accepted card lands once and re-applying is a no-op`() = runBlocking {
        val first = catalog.applyCollectiveCard(card())
        assertNotNull(first)

        assertEquals(1, dao.observeWorks().first().size)
        assertEquals(1, dao.observeEditions().first().size)
        // The catalogue claim lands on `work_sources` (the mirror), not on the
        // physical `sources` of an imported book.
        val claims = dao.observeWorkSourcesForWork(first!!.work.id).first()
        assertEquals(1, claims.count { it.sourceId == "soundbooks" })
        assertEquals("https://sound-books.net/kobzar", claims.first { it.sourceId == "soundbooks" }.sourceUrl)

        // The delta is idempotent: the same card re-read creates nothing new.
        val second = catalog.applyCollectiveCard(card())
        assertNotNull(second)
        assertEquals(1, dao.observeWorks().first().size)
        assertEquals(1, dao.observeEditions().first().size)
        assertEquals(1, dao.observeWorkSourcesForWork(first.work.id).first().size)
    }

    @Test
    fun `a rejected card lands nothing`() = runBlocking {
        val rejected = catalog.applyCollectiveCard(
            card(sourceId = "4read", sourceUrl = "https://4read.org/kobzar")
        )

        assertNull(rejected)
        assertTrue(dao.observeWorks().first().isEmpty())
        assertTrue(dao.observeEditions().first().isEmpty())
    }
}
