package com.slukhayka.audiobooks.data.facets

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.TombstoneEntity
import com.slukhayka.audiobooks.data.merge.MergeKey
import com.slukhayka.audiobooks.data.metadata.SubmissionAccessMode
import com.slukhayka.audiobooks.data.metadata.SubmissionChapter
import com.slukhayka.audiobooks.data.metadata.SubmissionPublication
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ADR-0035 / #605 — the local projection of published submissions against an
 * in-memory Room database (the LibraryImportSubmissionTest harness): a
 * second install materializes the source in the CATALOG shape (Works +
 * WorkSource + Edition + Source + tracks — never an Audiobooks/library row),
 * the same narration dedups to ONE Work, the same URL is a no-op, a
 * metadata-only publication materializes identity with NO playable tracks
 * (honest unavailable), and a locally tombstoned Work stays blocked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RoomSubmissionProjectionWriterTest {

    private lateinit var context: Context
    private lateinit var db: AudiobookDatabase
    private lateinit var dao: AudiobookDao
    private lateinit var writer: RoomSubmissionProjectionWriter

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.audiobookDao()
        writer = RoomSubmissionProjectionWriter(dao)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun publication(
        url: String,
        title: String = "Острів Дума",
        author: String? = "Стівен Кінг",
        narrator: String? = "Сергій Філатов",
        chapters: List<SubmissionChapter> = listOf(
            SubmissionChapter("Розділ 1", "https://www.youtube.com/watch?v=6XIPkMFZf-0"),
            SubmissionChapter("Розділ 2", "https://www.youtube.com/watch?v=biwxkjI06KA")
        ),
        accessMode: String = SubmissionAccessMode.YOUTUBE,
        submittedAt: Long = 1_000
    ) = SubmissionPublication(
        sourceUrl = url,
        accessMode = accessMode,
        title = title,
        author = author,
        narrator = narrator,
        chapters = chapters,
        verifiedAt = submittedAt - 100,
        submittedAt = submittedAt,
        submitterId = "device-1"
    )

    @Test
    fun `a published source materializes in the catalog shape`() = runBlocking {
        writer.apply(listOf(publication("https://www.youtube.com/playlist?list=PLking1")))

        val work = dao.findWorkByMergeKey(MergeKey.keyFor("Острів Дума", "Стівен Кінг"))
        assertTrue("the Work exists by mergeKey", work != null)
        val workSources = dao.getWorkSourcesForWorkSync(work!!.id)
        assertEquals(1, workSources.size)
        assertEquals("https://www.youtube.com/playlist?list=PLking1", workSources.single().sourceUrl)

        val sources = dao.getSourcesForBookSync(work.id)
        assertEquals(1, sources.size)
        assertEquals("youtube", sources.single().type)
        assertEquals(2, dao.getTracksForSourceSync(sources.single().id).size)
        assertEquals(2, dao.getChaptersListForBook(work.id).size)
        assertTrue(
            "tracks carry canonical watch URLs — the resolver seam streams them",
            dao.getTracksForSourceSync(sources.single().id)
                .all { it.url!!.startsWith("https://www.youtube.com/watch?v=") }
        )
        assertTrue(
            "the consumer gets a CATALOG entry, never a silent library book",
            dao.getAllAudiobooksOnce().isEmpty()
        )
    }

    @Test
    fun `the same narration with another url lands as a second source of ONE work`() = runBlocking {
        writer.apply(
            listOf(
                publication("https://www.youtube.com/playlist?list=PLking1"),
                publication("https://www.youtube.com/playlist?list=PLking1-variant")
            )
        )

        val work = dao.findWorkByMergeKey(MergeKey.keyFor("Острів Дума", "Стівен Кінг"))!!
        // Both sources land under the SAME Work (mergeKey dedup — one Work, never two).
        assertEquals(2, dao.getWorkSourcesForWorkSync(work.id).size)
        assertEquals(2, dao.getSourcesForBookSync(work.id).size)
        assertTrue("both sources belong to the one Work", dao.getSourcesForBookSync(work.id).all { it.bookId == work.id })
        assertEquals("chapters never duplicate", 2, dao.getChaptersListForBook(work.id).size)
    }

    @Test
    fun `the same url re-arriving is a no-op`() = runBlocking {
        val doc = publication("https://www.youtube.com/playlist?list=PLking1")
        writer.apply(listOf(doc))
        writer.apply(listOf(doc))

        val work = dao.findWorkByMergeKey(MergeKey.keyFor("Острів Дума", "Стівен Кінг"))!!
        assertEquals(1, dao.getWorkSourcesForWorkSync(work.id).size)
        assertEquals(1, dao.getSourcesForBookSync(work.id).size)
        assertEquals(2, dao.getChaptersListForBook(work.id).size)
    }

    @Test
    fun `a metadata-only publication is honestly unavailable - no fabricated tracks`() = runBlocking {
        writer.apply(
            listOf(publication("https://t.me/archivesofabookua/123", chapters = emptyList()))
        )

        val work = dao.findWorkByMergeKey(MergeKey.keyFor("Острів Дума", "Стівен Кінг"))
        assertTrue("the identity still materializes", work != null)
        assertEquals(1, dao.getWorkSourcesForWorkSync(work!!.id).size)
        assertTrue("no source rows - playback finds nothing honest", dao.getSourcesForBookSync(work.id).isEmpty())
        assertTrue(dao.getChaptersListForBook(work.id).isEmpty())
    }

    @Test
    fun `a tg preview publication materializes identity with honest unavailable`() = runBlocking {
        // ADR-0035 п. 13 / #606 — the RED verdict: a TG post publishes as
        // metadata only; another install sees the Work + the telegram
        // WorkSource claim, but NO Source rows and NO tracks — playback
        // honestly finds nothing, never a fabricated stream.
        writer.apply(
            listOf(
                publication(
                    url = "https://t.me/stivenkingua/168",
                    accessMode = SubmissionAccessMode.TG_PREVIEW,
                    chapters = emptyList(),
                    title = "Джералдова гра",
                    author = null,
                    narrator = "Alex Nekrasov"
                )
            )
        )

        // No author claim → the blank-identity contract: the Work lives under
        // its own stable id, never under a mergeKey.
        val workId = "w-telegram-${Integer.toHexString("https://t.me/stivenkingua/168".hashCode())}"
        val workSources = dao.getWorkSourcesForWorkSync(workId)
        assertEquals(1, workSources.size)
        assertEquals(com.slukhayka.audiobooks.data.source.SourceIds.TELEGRAM, workSources.single().sourceId)
        assertEquals("https://t.me/stivenkingua/168", workSources.single().sourceUrl)
        assertTrue("no Source rows - the honest unavailable surface", dao.getSourcesForBookSync(workId).isEmpty())
        assertTrue(dao.getChaptersListForBook(workId).isEmpty())
        assertTrue("never a silent library book", dao.getAllAudiobooksOnce().isEmpty())
    }

    @Test
    fun `an unknown access mode is skipped entirely`() = runBlocking {
        writer.apply(
            listOf(
                publication("https://example.com/future", accessMode = "some-future-mode")
            )
        )
        // No Work, no source, nothing materialized — an old app never guesses.
        assertNull(dao.findWorkByMergeKey(MergeKey.keyFor("Острів Дума", "Стівен Кінг")))
        assertTrue(dao.getSourcesForBookSync("").isEmpty())
    }

    @Test
    fun `a locally tombstoned work stays blocked`() = runBlocking {
        dao.insertTombstone(TombstoneEntity(bookId = MergeKey.keyFor("Острів Дума", "Стівен Кінг")))
        writer.apply(listOf(publication("https://www.youtube.com/playlist?list=PLking1")))

        assertNull(dao.findWorkByMergeKey(MergeKey.keyFor("Острів Дума", "Стівен Кінг")))
    }

    @Test
    fun `blank identity gets its own stable work and never merges`() = runBlocking {
        writer.apply(
            listOf(publication("https://www.youtube.com/watch?v=6XIPkMFZf-0", title = "Гаррі Поттер 1", author = null))
        )
        val works = dao.getWorkSourcesForWorkSync("w-youtube-${Integer.toHexString("https://www.youtube.com/watch?v=6XIPkMFZf-0".hashCode())}")
        assertEquals(1, works.size)
        assertNull(dao.findWorkByMergeKey(MergeKey.keyFor("Гаррі Поттер 1", "")))
    }

    @Test
    fun `the submitted edition keeps its narrator and observed chapter count`() = runBlocking {
        writer.apply(listOf(publication("https://www.youtube.com/playlist?list=PLking1")))
        val work = dao.findWorkByMergeKey(MergeKey.keyFor("Острів Дума", "Стівен Кінг"))!!
        val edition = dao.getEditionById(
            com.slukhayka.audiobooks.data.EditionId.forBook(
                MergeKey.keyFor("Острів Дума", "Стівен Кінг"),
                work.id,
                "Сергій Філатов"
            )
        )
        assertTrue(edition != null)
        assertEquals("Сергій Філатов", edition!!.narrator)
        assertEquals(2, edition.totalChapters)
    }
}