package com.slukhayka.audiobooks.data.merge

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.BookmarkEntity
import com.slukhayka.audiobooks.data.db.ChapterEntity
import com.slukhayka.audiobooks.data.db.EditionEntity
import com.slukhayka.audiobooks.data.db.PlaybackEventEntity
import com.slukhayka.audiobooks.data.db.PlaybackProgressEntity
import com.slukhayka.audiobooks.data.db.SeriesMemberEntity
import com.slukhayka.audiobooks.data.db.SourceEntity
import com.slukhayka.audiobooks.data.db.SourceTrackEntity
import com.slukhayka.audiobooks.data.db.WorkEntity
import com.slukhayka.audiobooks.data.db.WorkSourceEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Spec-27 (#184) BUG-002 — the one-time duplicate-Work merge over real
 * in-memory Room: two library rows that share a hardened merge key collapse
 * into one card, and the loser's progress / bookmarks / sources / carriers
 * move onto the survivor. Re-runs are idempotent; local (blank-url) books
 * are never merged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DuplicateWorkMergerRoomTest {

    private lateinit var db: AudiobookDatabase
    private lateinit var dao: AudiobookDao

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.audiobookDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private companion object {
        const val CLEAN_ID = "book-clean"
        const val RAW_ID = "book-raw"
        const val AUTHOR = "Джо Аберкромбі"
        const val CLEAN_TITLE = "Трохи ненависті"
        const val RAW_TITLE = "Трохи ненависті - АудіоКниги Українською"
        const val SOURCE_URL = "https://4read.org/trohy.html"
        // The identity as it was stored BEFORE the spec-27 hardening: the raw
        // title kept its brand words in the key.
        const val RAW_OLD_KEY = "трохи ненависті аудіокниги українською|джо аберкромбі"
        const val CLEAN_KEY = "трохи ненависті|джо аберкромбі"
        const val CLEAN_EDITION = "ed-clean"
        const val RAW_EDITION = "ed-raw"
    }

    private suspend fun seedDuplicatePair() {
        val cleanBook = AudiobookEntity(
            id = CLEAN_ID,
            title = CLEAN_TITLE,
            author = AUTHOR,
            narrator = "Читець",
            description = "Фікстура",
            coverDrawableRes = 0,
            genre = "Фентезі",
            sourceUrl = SOURCE_URL,
            isDownloaded = false,
            totalDurationSeconds = 3600L,
            totalChapters = 1,
            rating = 4.5f
        )
        dao.insertAudiobooks(listOf(cleanBook, cleanBook.copy(id = RAW_ID, title = RAW_TITLE)))

        dao.upsertWork(WorkEntity(id = CLEAN_KEY, mergeKey = CLEAN_KEY, title = CLEAN_TITLE, author = AUTHOR, addedAt = 1L))
        dao.upsertWork(WorkEntity(id = RAW_OLD_KEY, mergeKey = RAW_OLD_KEY, title = RAW_TITLE, author = AUTHOR, addedAt = 2L))
        dao.upsertLibraryEntry(CLEAN_ID, CLEAN_KEY, isFavorite = false, createdAt = 1L, downloadProgress = 0f)
        dao.upsertLibraryEntry(RAW_ID, RAW_OLD_KEY, isFavorite = false, createdAt = 2L, downloadProgress = 0f)

        dao.insertEdition(EditionEntity(id = CLEAN_EDITION, workId = CLEAN_ID, narrator = "Читець", totalChapters = 1, totalDurationSeconds = 3600L))
        dao.insertEdition(EditionEntity(id = RAW_EDITION, workId = RAW_ID, narrator = "Читець", totalChapters = 1, totalDurationSeconds = 3600L))
        dao.insertChapters(listOf(ChapterEntity("$CLEAN_ID-ch1", CLEAN_ID, 0, "Глава 1", 3600L, CLEAN_EDITION)))
        dao.insertChapters(listOf(ChapterEntity("$RAW_ID-ch1", RAW_ID, 0, "Глава 1", 3600L, RAW_EDITION)))

        dao.insertSources(listOf(SourceEntity("4read-$CLEAN_EDITION", CLEAN_ID, CLEAN_EDITION, "4read", SOURCE_URL, addedAt = 1L)))
        dao.insertSources(listOf(SourceEntity("4read-$RAW_EDITION", RAW_ID, RAW_EDITION, "4read", SOURCE_URL, addedAt = 2L)))
        dao.insertTracks(listOf(SourceTrackEntity("4read-$CLEAN_EDITION-tr1", "4read-$CLEAN_EDITION", 0, "$SOURCE_URL/trohy.mp3")))
        dao.insertTracks(listOf(SourceTrackEntity("4read-$RAW_EDITION-tr1", "4read-$RAW_EDITION", 0, "$SOURCE_URL/trohy.mp3")))

        // Both carriers point at the same (source, url) — the transfer must
        // dedupe, so the «N джерел» badge never double-counts.
        dao.upsertWorkSource(WorkSourceEntity(id = "$CLEAN_KEY|4read|url1", workId = CLEAN_KEY, sourceId = "4read", sourceUrl = SOURCE_URL, addedAt = 1L))
        dao.upsertWorkSource(WorkSourceEntity(id = "$RAW_OLD_KEY|4read|url1", workId = RAW_OLD_KEY, sourceId = "4read", sourceUrl = SOURCE_URL, addedAt = 2L))
        // The loser's universe membership follows the survivor.
        dao.upsertSeriesMember(SeriesMemberEntity(workId = RAW_OLD_KEY, seriesId = "first-law", position = 1, resolvedAt = 1L))
        // The loser's playback history rides along as a row to be cleaned.
        dao.insertPlaybackEvent(PlaybackEventEntity(bookId = RAW_ID, kind = "RESUME", timestamp = 1L))
    }

    private suspend fun seedProgress(bookId: String, editionId: String, positionSeconds: Long, lastListenedAt: Long) {
        dao.savePlaybackProgress(
            PlaybackProgressEntity(
                editionId = editionId,
                bookId = bookId,
                currentChapterIndex = 0,
                currentPositionSeconds = positionSeconds,
                lastListenedAt = lastListenedAt
            )
        )
    }

    private suspend fun seedBookmark(bookId: String, editionId: String?, id: Long) {
        dao.insertBookmark(
            BookmarkEntity(
                id = id,
                bookId = bookId,
                editionId = editionId,
                chapterIndex = 0,
                chapterTitle = "Глава 1",
                timestampSeconds = 120L,
                note = "Закладка",
                createdAt = 1L
            )
        )
    }

    @Test
    fun `spec27 - duplicate rows collapse into one card with the clean title`() = runBlocking {
        seedDuplicatePair()
        seedProgress(CLEAN_ID, CLEAN_EDITION, 100L, lastListenedAt = 1_000L)
        seedProgress(RAW_ID, RAW_EDITION, 300L, lastListenedAt = 2_000L)
        seedBookmark(CLEAN_ID, CLEAN_EDITION, id = 1L)
        seedBookmark(RAW_ID, RAW_EDITION, id = 2L)

        val removed = DuplicateWorkMerger(dao).mergeOnce()

        assertEquals(1, removed)
        // One card, the clean-titled row survives.
        val books = dao.getAllAudiobooksOnce()
        assertEquals(1, books.size)
        assertEquals(CLEAN_ID, books[0].id)
        assertEquals(CLEAN_TITLE, books[0].title)
        // The raw works row is gone; one Work remains.
        assertEquals(1, dao.countWorks())
        assertEquals(CLEAN_KEY, dao.observeWorks().first().single().id)
        // Listening state moved onto the survivor.
        val progress = dao.getAllPlaybackProgress().first()
        assertEquals(1, progress.size)
        assertEquals(CLEAN_ID, progress[0].bookId)
        // The loser's bookmarks transferred onto the survivor's card (they
        // accumulate — both rows now belong to the surviving book).
        val bookmarks = dao.getAllBookmarks().first()
        assertEquals(2, bookmarks.size)
        assertTrue(bookmarks.all { it.bookId == CLEAN_ID })
        // The duplicate chapters are dropped; the source survives on the card.
        assertEquals(1, dao.getChaptersListForBook(CLEAN_ID).size)
        assertEquals(0, dao.getChaptersListForBook(RAW_ID).size)
        assertEquals(1, dao.getSourcesForBookSync(CLEAN_ID).size)
        // The carrier dedupes: one source on the Work, not two.
        assertEquals(1, dao.countWorkSources())
        // The loser's universe membership moved to the survivor's Work.
        assertEquals(listOf(CLEAN_KEY), dao.getSeriesMembersForWork(CLEAN_KEY).map { it.workId })
        // The loser's event history is dropped with the row.
        assertEquals(0, dao.getPlaybackEventsForBookSource(RAW_ID, "").size)
    }

    @Test
    fun `spec27 - the newer listening progress wins on the survivor`() = runBlocking {
        seedDuplicatePair()
        // The survivor is older (100 s, t=1000); the raw duplicate is newer
        // (2500 s, t=2000) — the user's real position must survive.
        seedProgress(CLEAN_ID, CLEAN_EDITION, 100L, lastListenedAt = 1_000L)
        seedProgress(RAW_ID, RAW_EDITION, 2500L, lastListenedAt = 2_000L)

        DuplicateWorkMerger(dao).mergeOnce()

        val progress = dao.getAllPlaybackProgress().first()
        assertEquals(1, progress.size)
        assertEquals(CLEAN_ID, progress[0].bookId)
        assertEquals(CLEAN_EDITION, progress[0].editionId)
        assertEquals(2500L, progress[0].currentPositionSeconds)
    }

    @Test
    fun `spec27 - re-running the merger is idempotent`() = runBlocking {
        seedDuplicatePair()
        seedProgress(CLEAN_ID, CLEAN_EDITION, 100L, lastListenedAt = 1_000L)
        seedProgress(RAW_ID, RAW_EDITION, 300L, lastListenedAt = 2_000L)

        assertEquals(1, DuplicateWorkMerger(dao).mergeOnce())
        // A second run finds no group and changes nothing.
        assertEquals(0, DuplicateWorkMerger(dao).mergeOnce())
        assertEquals(1, dao.getAllAudiobooksOnce().size)
        assertEquals(1, dao.getAllPlaybackProgress().first().size)
    }

    @Test
    fun `spec27 - local blank-url books are never auto-merged`() = runBlocking {
        // Two local folder books whose titles share a ` - ` prefix — the user's
        // own files, with no SEO convention — must never collapse.
        val base = AudiobookEntity(
            id = "local-1",
            title = "Війна і мир - том 1",
            author = "Локальні файли",
            narrator = "Локальний аудіофайл",
            description = "Фікстура",
            coverDrawableRes = 0,
            genre = "Локальні",
            sourceUrl = "", // local
            isDownloaded = true,
            totalDurationSeconds = 0L,
            totalChapters = 1,
            rating = 0f
        )
        dao.insertAudiobooks(listOf(base, base.copy(id = "local-2", title = "Війна і мир - том 2")))
        listOf("local-1", "local-2").forEachIndexed { index, id ->
            val mergeKey = "local-work-${index + 1}"
            dao.upsertWork(
                WorkEntity(
                    id = mergeKey,
                    mergeKey = mergeKey,
                    title = "Війна і мир - том ${index + 1}",
                    author = "Локальні файли"
                )
            )
            dao.upsertLibraryEntry(id, mergeKey, isFavorite = false, createdAt = 0L, downloadProgress = 0f)
        }

        assertEquals(0, DuplicateWorkMerger(dao).mergeOnce())
        assertEquals(2, dao.getAllAudiobooksOnce().size)
        assertTrue(dao.getAllAudiobooksOnce().map { it.id }.containsAll(listOf("local-1", "local-2")))
    }
}
