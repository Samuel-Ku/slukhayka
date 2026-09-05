package com.slukhayka.audiobooks.data.imports

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.BookmarkEntity
import com.slukhayka.audiobooks.data.db.PlaybackProgressEntity
import com.slukhayka.audiobooks.data.metadata.BookProfile
import com.slukhayka.audiobooks.data.metadata.ProfileProvenance
import com.slukhayka.audiobooks.data.metadata.SharedBookMetaStore
import com.slukhayka.audiobooks.data.source.SourceAdapter
import com.slukhayka.audiobooks.data.source.SourceBook
import com.slukhayka.audiobooks.data.source.SourceBookDetail
import com.slukhayka.audiobooks.data.source.SourceChapter
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #430 — high-level 4read recovery coordinator, JVM seam.
 *
 * One scenario supplies captured page data, ordered observed audio candidates,
 * an existing or absent Edition, a playback verdict, a clean-request verdict
 * and a [SharedBookMetaStore] fake; assertions cover the resulting Library
 * rows, Source Tracks, resume command, publication decision and visible outcome.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BrowserRecoveryCoordinatorTest {

    @Test
    fun `recovery verifies requested chapter of the recovered source only`() = runBlocking {
        val page = "https://4read.org/kobzar-multisource.html"
        val original = detail(url = page, chapters = listOf(
            "Глава 1" to "https://s1.reasd.org/kobzar/old1.mp3",
            "Глава 2" to "https://s1.reasd.org/kobzar/old2.mp3"
        ))
        val bookId = seedBook(original)
        val source = dao.getSourcesForBookSync(bookId).single()
        val originalTracks = dao.getTracksForSourceSync(source.id)
        dao.insertSources(listOf(source.copy(id = "alternative", type = "soundbooks", url = "https://sound-books.net/kobzar")))
        dao.insertTracks(originalTracks.map {
            it.copy(id = "alternative-${it.trackIndex}", sourceId = "alternative", url = "https://other.invalid/${it.trackIndex}.mp3")
        })
        val refreshed = detail(url = page, chapters = listOf(
            "Глава 1" to "https://s1.reasd.org/kobzar/new1.mp3",
            "Глава 2" to "https://s1.reasd.org/kobzar/new2.mp3"
        ))
        val imports = LibraryImport(dao, context, listOf(fakeAdapter(mapOf("cap" to refreshed))))
        var verifiedUrl = ""
        val coordinator = BrowserRecoveryCoordinator(
            dao, imports,
            playbackVerifier = BrowserRecoveryCoordinator.PlaybackVerifier { _, url -> verifiedUrl = url; true }
        )

        val outcome = coordinator.recover(bookId, "4read", page, "cap", requestedChapterIndex = 1, requestedPositionMs = 30_000)

        assertEquals("https://s1.reasd.org/kobzar/new2.mp3", verifiedUrl)
        val success = outcome as BrowserRecoveryCoordinator.Outcome.Success
        assertEquals(1, success.resumeChapterIndex)
        assertEquals(30_000L, success.resumePositionMs)
        assertEquals("https://other.invalid/0.mp3", dao.getTracksForSourceSync("alternative").first().url)
    }

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

    private fun fakeAdapter(htmlToDetail: Map<String, SourceBookDetail?>): SourceAdapter =
        object : SourceAdapter {
            override val sourceId: String = "4read"
            override suspend fun search(query: String): List<SourceBook> = emptyList()
            override suspend fun fetchBookPage(url: String): SourceBookDetail = throw IllegalStateException("not used")
            override suspend fun fetchNew(limit: Int): List<SourceBook> = emptyList()
            override suspend fun parseCapturedPage(html: String, url: String): SourceBookDetail? = htmlToDetail[html]
        }

    private fun detail(
        title: String = "Кобзар",
        author: String = "Тарас Шевченко",
        narrator: String = "Іван Петров",
        url: String = "https://4read.org/kobzar.html",
        chapters: List<Pair<String, String>>
    ) = SourceBookDetail(
        title = title,
        author = author,
        narrator = narrator,
        url = url,
        chapters = chapters.map { (t, u) -> SourceChapter(t, u) }
    )

    private class FakeProfileStore : SharedBookMetaStore {
        val puts = mutableListOf<Pair<String, BookProfile>>()
        var shouldFailPut = false
        override suspend fun getDuration(editionId: String): Long? = null
        override suspend fun getDurations(editionIds: List<String>): Map<String, Long> = emptyMap()
        override suspend fun putDuration(editionId: String, durationSeconds: Long, provenance: com.slukhayka.audiobooks.data.metadata.DurationProvenance) = Unit
        override suspend fun getProfile(sourceId: String, editionId: String): BookProfile? = null
        override suspend fun getProfileEntry(sourceId: String, editionId: String): com.slukhayka.audiobooks.data.metadata.SharedProfileEntry? = null
        override suspend fun putProfile(sourceId: String, editionId: String, profile: BookProfile, provenance: ProfileProvenance) {
            if (shouldFailPut) throw IllegalStateException("firestore denied")
            puts += sourceId to profile
        }
        override suspend fun getCover(mergeKey: String): String? = null
        override suspend fun getCovers(mergeKeys: List<String>): Map<String, String> = emptyMap()
        override suspend fun putCover(mergeKey: String, coverUrl: String, provenance: com.slukhayka.audiobooks.data.metadata.CoverProvenance) = Unit
    }

    private suspend fun seedBook(detail: SourceBookDetail): String {
        val adapter = fakeAdapter(mapOf("seed" to detail))
        val imports = LibraryImport(dao, context, listOf(adapter))
        val book = imports.importWebSourcePage("4read", detail.url, "seed")
            ?: error("seed failed")
        // Bypass writeBackProfile gate for seed (4read stays local) — seed directly via importBookFromSource with writeBack false is already done;
        // For test, we want the book persisted regardless of profile, so seed via direct import.
        return book.id
    }

    @Test
    fun `new import success closes browser and starts at chapter 0`() = runBlocking {
        val newDetail = detail(
            chapters = listOf("Глава 1" to "https://s1.reasd.org/kobzar/new1.mp3", "Глава 2" to "https://s1.reasd.org/kobzar/new2.mp3")
        )
        val imports = LibraryImport(dao, context, listOf(fakeAdapter(mapOf("html" to newDetail))))
        val coordinator = BrowserRecoveryCoordinator(
            dao = dao,
            libraryImport = imports,
            playbackVerifier = BrowserRecoveryCoordinator.PlaybackVerifier { _, _ -> true }
        )

        val outcome = coordinator.recover(
            bookId = null,
            sourceId = "4read",
            url = "https://4read.org/kobzar.html",
            html = "html",
            capturedAudioUrls = emptyList()
        )

        assertTrue(outcome is BrowserRecoveryCoordinator.Outcome.Success)
        val succ = outcome as BrowserRecoveryCoordinator.Outcome.Success
        assertTrue(succ.shouldCloseBrowser)
        assertEquals(0, succ.resumeChapterIndex)
        assertEquals(0L, succ.resumePositionMs)
        assertTrue(succ.isNewImport)
        assertNotNull(dao.getAudiobookById(succ.book.id))
    }

    @Test
    fun `recovery success preserves chapter and position and closes browser`() = runBlocking {
        val original = detail(chapters = listOf("Глава 1" to "https://s1.reasd.org/kobzar/old1.mp3", "Глава 2" to "https://s1.reasd.org/kobzar/old2.mp3"))
        val bookId = seedBook(original)

        val refreshed = detail(chapters = listOf("Глава 1" to "https://s1.reasd.org/kobzar/new1.mp3", "Глава 2" to "https://s1.reasd.org/kobzar/new2.mp3"))
        val imports = LibraryImport(dao, context, listOf(fakeAdapter(mapOf("cap" to refreshed))))
        // Seed listening progress at chapter 1, position 30s
        val edition = dao.getEditionForWork(bookId)!!
        dao.savePlaybackProgress(
            com.slukhayka.audiobooks.data.db.PlaybackProgressEntity(
                editionId = edition.id, bookId = bookId, currentChapterIndex = 1, currentPositionSeconds = 30L, lastListenedAt = System.currentTimeMillis()
            )
        )

        val coordinator = BrowserRecoveryCoordinator(
            dao = dao,
            libraryImport = imports,
            playbackVerifier = BrowserRecoveryCoordinator.PlaybackVerifier { _, url -> url.contains("new2.mp3") }
        )

        val outcome = coordinator.recover(
            bookId = bookId,
            sourceId = "4read",
            url = "https://4read.org/kobzar.html",
            html = "cap",
            requestedChapterIndex = 1,
            requestedPositionMs = 30_000L
        )

        assertTrue(outcome is BrowserRecoveryCoordinator.Outcome.Success)
        val succ = outcome as BrowserRecoveryCoordinator.Outcome.Success
        assertTrue(succ.shouldCloseBrowser)
        assertEquals(1, succ.resumeChapterIndex)
        assertEquals(30_000L, succ.resumePositionMs)
        // Tracks updated
        val tracks = dao.getTracksForBookSync(bookId).sortedBy { it.trackIndex }
        assertEquals("https://s1.reasd.org/kobzar/new1.mp3", tracks[0].url)
    }

    @Test
    fun `http prefix alone is not success - needs player verdict`() = runBlocking {
        val original = detail(chapters = listOf("Глава 1" to "https://s1.reasd.org/kobzar/old1.mp3"))
        val bookId = seedBook(original)

        val refreshed = detail(chapters = listOf("Глава 1" to "https://s1.reasd.org/kobzar/new1.mp3"))
        val imports = LibraryImport(dao, context, listOf(fakeAdapter(mapOf("cap" to refreshed))))
        val coordinator = BrowserRecoveryCoordinator(
            dao = dao,
            libraryImport = imports,
            playbackVerifier = BrowserRecoveryCoordinator.PlaybackVerifier { _, _ -> false } // Player says dead
        )

        val outcome = coordinator.recover(bookId, "4read", "https://4read.org/kobzar.html", "cap")

        assertTrue(outcome is BrowserRecoveryCoordinator.Outcome.Failure)
        val fail = outcome as BrowserRecoveryCoordinator.Outcome.Failure
        assertFalse(fail.shouldCloseBrowser)
        assertTrue(fail.message.contains("Аудіо"))
        // Tracks must be rolled back on verifier failure — dead URL must not persist.
        val tracks = dao.getTracksForBookSync(bookId).sortedBy { it.trackIndex }
        assertEquals("https://s1.reasd.org/kobzar/old1.mp3", tracks[0].url)
    }

    @Test
    fun `cancelling a structure repair keeps every stored recovery state untouched`() = runBlocking {
        val original = detail(chapters = listOf("Глава 1" to "https://s1.reasd.org/kobzar/old1.mp3"))
        val bookId = seedBook(original)
        val edition = requireNotNull(dao.getEditionForWork(bookId))
        val originalTrack = dao.getTracksForBookSync(bookId).single()
        dao.savePlaybackProgress(
            PlaybackProgressEntity(
                editionId = edition.id,
                bookId = bookId,
                currentChapterIndex = 0,
                currentPositionSeconds = 42L
            )
        )
        dao.insertBookmark(
            BookmarkEntity(
                bookId = bookId,
                editionId = edition.id,
                chapterIndex = 0,
                chapterTitle = "Глава 1",
                timestampSeconds = 42L,
                note = "Не загубити"
            )
        )
        dao.updateTrackDownloadState(originalTrack.id, isDownloaded = true, filePath = "/tmp/kobzar.mp3")
        val refreshed = detail(chapters = listOf(
            "Глава 1" to "https://s1.reasd.org/kobzar/new1.mp3",
            "Глава 2" to "https://s1.reasd.org/kobzar/new2.mp3"
        ))
        val coordinator = BrowserRecoveryCoordinator(
            dao = dao,
            libraryImport = LibraryImport(dao, context, listOf(fakeAdapter(mapOf("cap" to refreshed))))
        )

        val outcome = coordinator.recover(bookId, "4read", "https://4read.org/kobzar.html", "cap")

        assertTrue(outcome is BrowserRecoveryCoordinator.Outcome.StructureMismatch)
        val mismatch = outcome as BrowserRecoveryCoordinator.Outcome.StructureMismatch
        assertEquals(1, mismatch.storedChapterCount)
        assertEquals(2, mismatch.capturedChapterCount)
        assertFalse(mismatch.shouldCloseBrowser)
        assertEquals(listOf("Глава 1"), dao.getChaptersListForBook(bookId).map { it.title })
        assertEquals(42L, dao.getPlaybackProgressSync(bookId)?.currentPositionSeconds)
        assertEquals("Не загубити", dao.getBookmarksForBook(bookId).first().single().note)
        val preservedTrack = dao.getTracksForBookSync(bookId).single()
        assertEquals("https://s1.reasd.org/kobzar/old1.mp3", preservedTrack.url)
        assertTrue(preservedTrack.isDownloaded)
        assertEquals("/tmp/kobzar.mp3", preservedTrack.localFilePath)
    }

    @Test
    fun `confirmed structure repair replaces topology and clears only chapter scoped state`() = runBlocking {
        val original = detail(chapters = listOf("Глава 1" to "https://s1.reasd.org/kobzar/old1.mp3"))
        val bookId = seedBook(original)
        val edition = requireNotNull(dao.getEditionForWork(bookId))
        dao.savePlaybackProgress(PlaybackProgressEntity(edition.id, bookId, currentPositionSeconds = 42L))
        dao.insertBookmark(BookmarkEntity(bookId = bookId, editionId = edition.id, chapterIndex = 0, chapterTitle = "Глава 1", timestampSeconds = 42L, note = "Стара"))
        val originalTrack = dao.getTracksForBookSync(bookId).single()
        val staleFile = File.createTempFile("kobzar-repair", ".mp3").apply { writeBytes(byteArrayOf(1)) }
        dao.updateTrackDownloadState(originalTrack.id, true, staleFile.absolutePath)
        val alternateSource = com.slukhayka.audiobooks.data.db.SourceEntity(
            id = "local-${edition.id}",
            bookId = bookId,
            editionId = edition.id,
            type = "local",
            url = ""
        )
        dao.insertSources(listOf(alternateSource))
        dao.insertTracks(listOf(com.slukhayka.audiobooks.data.db.SourceTrackEntity(
            id = "${alternateSource.id}_tr_1",
            sourceId = alternateSource.id,
            trackIndex = 0,
            url = "/tmp/alternate.mp3",
            localFilePath = "/tmp/alternate.mp3",
            isDownloaded = true
        )))
        val updated = detail(chapters = listOf(
            "Глава 1" to "https://s1.reasd.org/kobzar/1.mp3",
            "Глава 2" to "https://s1.reasd.org/kobzar/2.mp3"
        ))
        val imports = LibraryImport(dao, context, listOf(fakeAdapter(mapOf("updated" to updated))))

        val repaired = imports.repairConfirmedWebSourceStructure(bookId, "4read", original.url, "updated")

        assertEquals(bookId, repaired?.id)
        assertEquals(listOf("Глава 1", "Глава 2"), dao.getChaptersListForBook(bookId).map { it.title })
        val repairedSource = requireNotNull(dao.getSourcesForBookSync(bookId).singleOrNull { it.type == "4read" && it.url == original.url })
        assertEquals(listOf("https://s1.reasd.org/kobzar/1.mp3", "https://s1.reasd.org/kobzar/2.mp3"), dao.getTracksForSourceSync(repairedSource.id).map { it.url })
        assertNull(dao.getPlaybackProgressSync(bookId))
        assertTrue(dao.getBookmarksForBook(bookId).first().isEmpty())
        assertTrue(dao.getTracksForSourceSync(alternateSource.id).isEmpty())
        assertEquals(repairedSource.id, dao.getSourcesForBookSync(bookId).single { it.type == "4read" }.id)
        assertFalse(staleFile.exists())
        assertFalse(requireNotNull(dao.getAudiobookById(bookId)).isDownloaded)
    }

    @Test
    fun `a different captured Work is not reported as this Edition's structure mismatch`() = runBlocking {
        val original = detail(chapters = listOf("Глава 1" to "https://s1.reasd.org/kobzar/old1.mp3"))
        val bookId = seedBook(original)
        val otherWork = detail(
            title = "Лісова пісня",
            author = "Леся Українка",
            chapters = listOf(
                "Глава 1" to "https://s1.reasd.org/lisova/1.mp3",
                "Глава 2" to "https://s1.reasd.org/lisova/2.mp3"
            )
        )
        val coordinator = BrowserRecoveryCoordinator(
            dao = dao,
            libraryImport = LibraryImport(dao, context, listOf(fakeAdapter(mapOf("other" to otherWork))))
        )

        val outcome = coordinator.recover(bookId, "4read", "https://4read.org/kobzar.html", "other")

        assertTrue(outcome is BrowserRecoveryCoordinator.Outcome.Failure)
        assertEquals("https://s1.reasd.org/kobzar/old1.mp3", dao.getTracksForBookSync(bookId).single().url)
    }

    @Test
    fun `a different narrator is not reported as this Edition's structure mismatch`() = runBlocking {
        val original = detail(chapters = listOf("Глава 1" to "https://s1.reasd.org/kobzar/old1.mp3"))
        val bookId = seedBook(original)
        val otherNarration = detail(
            narrator = "Марія Романенко",
            chapters = listOf(
                "Глава 1" to "https://s1.reasd.org/kobzar-maria/1.mp3",
                "Глава 2" to "https://s1.reasd.org/kobzar-maria/2.mp3"
            )
        )
        val coordinator = BrowserRecoveryCoordinator(
            dao = dao,
            libraryImport = LibraryImport(dao, context, listOf(fakeAdapter(mapOf("other-narrator" to otherNarration))))
        )

        val outcome = coordinator.recover(bookId, "4read", "https://4read.org/kobzar.html", "other-narrator")

        assertTrue(outcome is BrowserRecoveryCoordinator.Outcome.Failure)
    }

    @Test
    fun `challenge page explains that browser verification must finish`() = runBlocking {
        val original = detail(chapters = listOf("Глава 1" to "https://s1.reasd.org/kobzar/old1.mp3"))
        val bookId = seedBook(original)
        val coordinator = BrowserRecoveryCoordinator(
            dao = dao,
            libraryImport = LibraryImport(dao, context, listOf(fakeAdapter(mapOf("challenge" to null))))
        )

        val outcome = coordinator.recover(
            bookId,
            "4read",
            "https://4read.org/kobzar.html",
            "<html><title>Just a moment...</title><body>Checking your browser before accessing 4read.</body></html>"
        )

        assertTrue(outcome is BrowserRecoveryCoordinator.Outcome.Failure)
        assertTrue((outcome as BrowserRecoveryCoordinator.Outcome.Failure).message.contains("перевіряє браузер"))
    }

    @Test
    fun `empty or challenge page keeps browser open with honest message`() = runBlocking {
        val original = detail(chapters = listOf("Глава 1" to "https://s1.reasd.org/kobzar/old1.mp3"))
        val bookId = seedBook(original)

        val emptyDetail = detail(chapters = emptyList())
        val imports = LibraryImport(dao, context, listOf(fakeAdapter(mapOf("empty" to emptyDetail))))
        val coordinator = BrowserRecoveryCoordinator(
            dao = dao,
            libraryImport = imports,
            playbackVerifier = BrowserRecoveryCoordinator.PlaybackVerifier { _, _ -> true }
        )

        val outcome = coordinator.recover(bookId, "4read", "https://4read.org/kobzar.html", "empty")
        assertTrue(outcome is BrowserRecoveryCoordinator.Outcome.Failure)
        assertFalse((outcome as BrowserRecoveryCoordinator.Outcome.Failure).shouldCloseBrowser)
    }

    @Test
    fun `dead candidate via 403 is failure browser stays open`() = runBlocking {
        val original = detail(chapters = listOf("Глава 1" to "https://s1.reasd.org/kobzar/old1.mp3"))
        val bookId = seedBook(original)

        // Captured html yields playable URLs but verifier says 403
        val refreshed = detail(chapters = listOf("Глава 1" to "https://s1.reasd.org/kobzar/new1.mp3"))
        val imports = LibraryImport(dao, context, listOf(fakeAdapter(mapOf("cap" to refreshed))))
        val coordinator = BrowserRecoveryCoordinator(
            dao = dao,
            libraryImport = imports,
            playbackVerifier = BrowserRecoveryCoordinator.PlaybackVerifier { _, _ -> false }
        )

        val outcome = coordinator.recover(bookId, "4read", "https://4read.org/kobzar.html", "cap")
        assertTrue(outcome is BrowserRecoveryCoordinator.Outcome.Failure)
    }

    @Test
    fun `new import survives recovery verdict failure and stays persisted in Room`() = runBlocking {
        // Regression (#430, hypothesis 3): a book imported via "Додати цю книгу"
        // (new import, bookId == null) must NOT be deleted from the DB when the
        // Player verdict fails. The import already committed a row; deletion here
        // is unrecoverable data loss after force-stop.
        val newDetail = detail(
            title = "Сни",
            chapters = listOf(
                "Глава 1" to "https://s1.reasd.org/sni/new1.mp3",
                "Глава 2" to "https://s1.reasd.org/sni/new2.mp3"
            )
        )
        val imports = LibraryImport(dao, context, listOf(fakeAdapter(mapOf("html" to newDetail))))
        val coordinator = BrowserRecoveryCoordinator(
            dao = dao,
            libraryImport = imports,
            playbackVerifier = BrowserRecoveryCoordinator.PlaybackVerifier { _, _ -> false } // dead link / 403
        )

        val outcome = coordinator.recover(
            bookId = null,
            sourceId = "4read",
            url = "https://4read.org/sni.html",
            html = "html",
            capturedAudioUrls = emptyList()
        )

        // Verdict failed — visible outcome is a failure, browser stays open.
        assertTrue(outcome is BrowserRecoveryCoordinator.Outcome.Failure)
        // But the just-imported book must remain persisted in Room.
        val persisted = dao.getAllAudiobooksOnce()
        assertEquals(1, persisted.size)
        assertEquals("Сни", persisted.first().title)
    }

    @Test
    fun `search fallback url is generated when exact source url missing`() = runBlocking {
        val original = detail(title = "Кобзар", author = "Тарас Шевченко", chapters = listOf("Глава 1" to "https://s1.reasd.org/kobzar/old1.mp3"))
        val bookId = seedBook(original)

        // Simulate missing exact URL by passing blank url and checking helper
        val searchUrl = BrowserRecoveryCoordinator.searchUrlFor("Кобзар")
        assertTrue(searchUrl.startsWith("https://4read.org/index.php?do=search"))
        assertTrue(searchUrl.contains("Кобзар") || searchUrl.contains("%D0%9A"))

        val entryUrl = BrowserRecoveryCoordinator.recoveryEntryUrl(dao, bookId)
        // Should be exact source URL when present
        assertEquals("https://4read.org/kobzar.html", entryUrl)

        // Delete the source to simulate missing URL
        dao.deleteSourcesForBook(bookId)
        val fallback = BrowserRecoveryCoordinator.recoveryEntryUrl(dao, bookId)
        assertTrue(fallback.contains("search"))
    }

    @Test
    fun `shareable candidate is published only after clean probe success`() = runBlocking {
        val original = detail(chapters = listOf("Глава 1" to "https://s1.reasd.org/kobzar/old1.mp3"))
        val bookId = seedBook(original)

        val refreshed = detail(chapters = listOf("Глава 1" to "https://s1.reasd.org/kobzar/new1.mp3"))
        val imports = LibraryImport(dao, context, listOf(fakeAdapter(mapOf("cap" to refreshed))))
        val store = FakeProfileStore()
        var cleanProbeCalled = false
        val coordinator = BrowserRecoveryCoordinator(
            dao = dao,
            libraryImport = imports,
            profileStore = store,
            playbackVerifier = BrowserRecoveryCoordinator.PlaybackVerifier { _, _ -> true },
            cleanProbe = BrowserRecoveryCoordinator.CleanProbe {
                cleanProbeCalled = true
                true
            }
        )

        val outcome = coordinator.recover(bookId, "4read", "https://4read.org/kobzar.html", "cap")
        assertTrue(outcome is BrowserRecoveryCoordinator.Outcome.Success)
        assertTrue(cleanProbeCalled)
        assertEquals(1, store.puts.size)
    }

    @Test
    fun `cookie-bound candidate stays local when clean probe fails`() = runBlocking {
        val original = detail(chapters = listOf("Глава 1" to "https://s1.reasd.org/kobzar/old1.mp3"))
        val bookId = seedBook(original)

        val refreshed = detail(chapters = listOf("Глава 1" to "https://s1.reasd.org/kobzar/new1.mp3"))
        val imports = LibraryImport(dao, context, listOf(fakeAdapter(mapOf("cap" to refreshed))))
        val store = FakeProfileStore()
        val coordinator = BrowserRecoveryCoordinator(
            dao = dao,
            libraryImport = imports,
            profileStore = store,
            playbackVerifier = BrowserRecoveryCoordinator.PlaybackVerifier { _, _ -> true },
            cleanProbe = BrowserRecoveryCoordinator.CleanProbe { false } // clean probe fails → cookie-bound
        )

        val outcome = coordinator.recover(bookId, "4read", "https://4read.org/kobzar.html", "cap")
        assertTrue(outcome is BrowserRecoveryCoordinator.Outcome.Success)
        // Local success, but not published
        assertEquals(0, store.puts.size)
        assertFalse((outcome as BrowserRecoveryCoordinator.Outcome.Success).publishedProfile)
    }

    @Test
    fun `unavailable firebase with failed put stays success and silent`() = runBlocking {
        val original = detail(chapters = listOf("Глава 1" to "https://s1.reasd.org/kobzar/old1.mp3"))
        val bookId = seedBook(original)

        val refreshed = detail(chapters = listOf("Глава 1" to "https://s1.reasd.org/kobzar/new1.mp3"))
        val imports = LibraryImport(dao, context, listOf(fakeAdapter(mapOf("cap" to refreshed))))
        val store = FakeProfileStore().apply { shouldFailPut = true }
        val coordinator = BrowserRecoveryCoordinator(
            dao = dao,
            libraryImport = imports,
            profileStore = store,
            playbackVerifier = BrowserRecoveryCoordinator.PlaybackVerifier { _, _ -> true },
            cleanProbe = BrowserRecoveryCoordinator.CleanProbe { true }
        )

        val outcome = coordinator.recover(bookId, "4read", "https://4read.org/kobzar.html", "cap")
        assertTrue(outcome is BrowserRecoveryCoordinator.Outcome.Success)
        // Local success despite failed put
        assertFalse((outcome as BrowserRecoveryCoordinator.Outcome.Success).publishedProfile)
    }

    // --- #470: automatic non-destructive structure repair, bounded --------

    /** A seed + capture pair whose chapter count differs (same Work identity). */
    private suspend fun mismatchFixture(): Triple<LibraryImport, String, SourceBookDetail> {
        val original = detail(chapters = listOf("Глава 1" to "https://s1.reasd.org/kobzar/old1.mp3"))
        val bookId = seedBook(original)
        val refreshed = detail(chapters = listOf(
            "Глава 1" to "https://s1.reasd.org/kobzar/new1.mp3",
            "Глава 2" to "https://s1.reasd.org/kobzar/new2.mp3"
        ))
        return Triple(LibraryImport(dao, context, listOf(fakeAdapter(mapOf("cap" to refreshed)))), bookId, refreshed)
    }

    @Test
    fun `non-destructive structure mismatch repairs automatically without the listener's confirmation`() = runBlocking {
        val (imports, bookId, _) = mismatchFixture()
        val coordinator = BrowserRecoveryCoordinator(
            dao = dao,
            libraryImport = imports,
            playbackVerifier = BrowserRecoveryCoordinator.PlaybackVerifier { _, _ -> true },
            repairMemo = AutoRepairMemo()
        )

        val outcome = coordinator.recover(bookId, "4read", "https://4read.org/kobzar.html", "cap")

        // No dialog: the repair lost nothing (no files, progress or bookmarks),
        // so it ran automatically and reports itself honestly.
        assertTrue(outcome is BrowserRecoveryCoordinator.Outcome.Success)
        val success = outcome as BrowserRecoveryCoordinator.Outcome.Success
        assertTrue(success.autoRepairedStructure)
        assertEquals(listOf("Глава 1", "Глава 2"), dao.getChaptersListForBook(bookId).map { it.title })
        val source = requireNotNull(dao.getSourcesForBookSync(bookId).singleOrNull { it.type == "4read" })
        assertEquals(
            listOf("https://s1.reasd.org/kobzar/new1.mp3", "https://s1.reasd.org/kobzar/new2.mp3"),
            dao.getTracksForSourceSync(source.id).map { it.url }
        )
    }

    @Test
    fun `a destructive structure mismatch keeps the interactive confirmation dialog`() = runBlocking {
        val (imports, bookId, _) = mismatchFixture()
        val edition = requireNotNull(dao.getEditionForWork(bookId))
        // One bookmark row is enough for the repair to cost the listener data.
        dao.insertBookmark(
            BookmarkEntity(
                bookId = bookId,
                editionId = edition.id,
                chapterIndex = 0,
                chapterTitle = "Глава 1",
                timestampSeconds = 42L,
                note = "Не загубити"
            )
        )
        val coordinator = BrowserRecoveryCoordinator(
            dao = dao,
            libraryImport = imports,
            playbackVerifier = BrowserRecoveryCoordinator.PlaybackVerifier { _, _ -> true },
            repairMemo = AutoRepairMemo()
        )

        val outcome = coordinator.recover(bookId, "4read", "https://4read.org/kobzar.html", "cap")

        assertTrue(outcome is BrowserRecoveryCoordinator.Outcome.StructureMismatch)
        assertEquals(
            listOf("Глава 1"),
            dao.getChaptersListForBook(bookId).map { it.title }
        )
        assertEquals("Не загубити", dao.getBookmarksForBook(bookId).first().single().note)
    }

    @Test
    fun `a failed automatic repair falls back to the dialog and does not re-run within the negative window`() = runBlocking {
        val (imports, bookId, _) = mismatchFixture()
        var now = 1_000_000L
        val memo = AutoRepairMemo { now }
        var repairCalls = 0
        val coordinator = BrowserRecoveryCoordinator(
            dao = dao,
            libraryImport = imports,
            playbackVerifier = BrowserRecoveryCoordinator.PlaybackVerifier { _, _ -> true },
            repairMemo = memo,
            structureRepair = { _, _, _, _, _ ->
                repairCalls += 1
                null // the repair fails
            }
        )

        val first = coordinator.recover(bookId, "4read", "https://4read.org/kobzar.html", "cap")
        assertTrue(first is BrowserRecoveryCoordinator.Outcome.StructureMismatch)
        assertEquals(1, repairCalls)

        // A later capture within the 15-minute negative window never re-runs
        // the automatic repair (ADR-0019: no auto-loops) — the dialog stays.
        now += com.slukhayka.audiobooks.data.catalog.CatalogAvailabilityPolicy.NEGATIVE_TTL_MS - 1
        val second = coordinator.recover(bookId, "4read", "https://4read.org/kobzar.html", "cap")
        assertTrue(second is BrowserRecoveryCoordinator.Outcome.StructureMismatch)
        assertEquals(1, repairCalls)

        // Past the negative boundary the verdict is stale — exactly one new
        // automatic attempt is allowed, never an unbounded loop.
        now += 1
        coordinator.recover(bookId, "4read", "https://4read.org/kobzar.html", "cap")
        assertEquals(2, repairCalls)
    }
}
