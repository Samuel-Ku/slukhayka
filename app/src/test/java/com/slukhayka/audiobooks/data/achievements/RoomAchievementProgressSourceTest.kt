package com.slukhayka.audiobooks.data.achievements

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.*
import com.slukhayka.audiobooks.testing.TestDataFactory
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class RoomAchievementProgressSourceTest {
    @Test fun `actual partial Room data never fabricates intent completion series or full downloads`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context,AudiobookDatabase::class.java).allowMainThreadQueries().build()
        val file = File.createTempFile("699-download-proof", ".mp3",context.cacheDir).apply { writeBytes(byteArrayOf(1)) }
        try {
            val store = RoomAchievementStore(database.achievementDao())
            val source = RoomAchievementProgressSource(database.achievementDao(),store,setOf("sluhay","youtube"))
            val empty = source.observe().first()
            assertEquals(0L,empty.explicitBooks); assertEquals(0L,empty.downloadedBooks)
            assertEquals(setOf("sluhay","youtube"),empty.registeredSourceIds)
            assertEquals(emptySet<AchievementSeriesMembership>(),empty.knownSeriesMemberships)
            val dao=database.audiobookDao()
            val book=TestDataFactory.dataBooks().first().copy(totalChapters=2)
            val chapters=TestDataFactory.dataChapters().filter { it.bookId==book.id }.take(2)
            dao.insertAudiobooks(listOf(book)); dao.insertChapters(chapters)
            dao.upsertLibraryEntry(book.id,book.id,false,123L,0f)
            dao.savePlaybackProgress(PlaybackProgressEntity("edition",book.id,0,0,123L,true))
            dao.upsertSeriesMember(SeriesMemberEntity(book.id,"partial-series",2))
            val sources=listOf("a","b").map { SourceEntity(it,book.id,type="sluhay",url="https://sluhay.com/$it") }
            dao.insertSources(sources)
            dao.insertTracks(listOf(SourceTrackEntity("a0","a",0,"url",file.absolutePath,isDownloaded=true),
                SourceTrackEntity("b1","b",1,"url",file.absolutePath,isDownloaded=true)))
            val partial=source.observe().first()
            assertEquals(0L,partial.explicitBooks); assertEquals(0L,partial.completedBooks); assertEquals(0L,partial.downloadedBooks)
            assertEquals(setOf(AchievementSeriesMembership("partial-series",book.id,2)),partial.knownSeriesMemberships)
            assertEquals(emptyList<AchievementDefinition>(),AchievementEvaluator.evaluate(partial,emptySet()))
            dao.updateLibraryEntryOrigin(book.id,"EXPLICIT_SAVE")
            dao.insertTracks(listOf(SourceTrackEntity("a1","a",1,"url",file.absolutePath,isDownloaded=true)))
            store.recordFact(AchievementFact.PLAYBACK_STARTED)
            val actual=source.observe().first()
            assertEquals(1L,actual.explicitBooks); assertEquals(1L,actual.downloadedBooks); assertEquals(1L,actual.playbackStarts)
            dao.updateBookStats(book.id,3,0L)
            assertEquals("missing known logical chapter is not a complete download",0L,source.observe().first().downloadedBooks)
            file.delete()
            assertEquals(0L,source.observe().first().downloadedBooks)
        } finally { database.close(); file.delete() }
    }

    /**
     * #700 (T2) — the speed bands must count STORED preferences and nothing
     * else.
     *
     * A NULL `preferredSpeed` means "use the global default", which is not a
     * claim that the book was heard at 1x — so it belongs to neither band. This
     * runs the REAL queries against the REAL schema, because the honesty lives
     * in the SQL (`WHERE preferredSpeed > 1.5`), not in Kotlin.
     */
    @Test fun `speed bands count stored preferences and ignore the null default`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val dao = database.audiobookDao()
            val achievementDao = database.achievementDao()

            // Three books: one explicitly fast, one explicitly slow, one with
            // NO stored preference (the global-default case).
            // The factory ships three books; the boundary case needs a fourth,
            // built here from one of them so every non-id column stays valid.
            val books = TestDataFactory.dataBooks().let { base ->
                base + listOf(base.first().copy(id = "boundary-book", title = "Межова"))
            }
            dao.insertAudiobooks(books)
            dao.savePlaybackProgress(PlaybackProgressEntity("e0", books[0].id, 0, 0, 1L, false, preferredSpeed = 2.0f))
            dao.savePlaybackProgress(PlaybackProgressEntity("e1", books[1].id, 0, 0, 1L, false, preferredSpeed = 0.5f))
            dao.savePlaybackProgress(PlaybackProgressEntity("e2", books[2].id, 0, 0, 1L, false, preferredSpeed = null))
            // A book BETWEEN the two bands: faster than the slow bound but NOT
            // above 1.5x. Without it a wrong fast bound (say 0.5) would still
            // count one book and the test would pass — which is exactly what my
            // first version of this test did.
            dao.savePlaybackProgress(PlaybackProgressEntity("e3", books[3].id, 0, 0, 1L, false, preferredSpeed = 1.2f))

            assertEquals("швидких мусить бути рівно одна — 1.2x не швидкий", 1L, achievementDao.observeFastBooks().first())
            assertEquals("повільних мусить бути рівно одна — 1.2x не повільний", 1L, achievementDao.observeSlowBooks().first())

            // And the ladder does not open on a single book — the thresholds are
            // the spec's, not mine.
            val snapshot = RoomAchievementProgressSource(
                achievementDao, RoomAchievementStore(achievementDao), emptySet()
            ).observe().first()
            assertEquals(1L, snapshot.fastBooks)
            assertTrue(
                "10 книг — поріг «Швидкісного», одна його не відкриває",
                AchievementEvaluator.evaluate(snapshot, emptySet()).map { it.id }.none {
                    it == "speedster_10"
                }
            )
        } finally {
            database.close()
        }
    }

    /**
     * #700 (T2) — a blank note is a BOOKMARK, not a note.
     *
     * `note` is non-null but may be blank, so counting rows would inflate the
     * note award with bookmarks the listener never wrote anything on. The test
     * puts a real note, an EMPTY one and a WHITESPACE-only one side by side —
     * the last two must not count, and the whitespace case is what proves the
     * query trims rather than comparing to "".
     */
    @Test fun `only a written note counts as a note`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val dao = database.audiobookDao()
            val achievementDao = database.achievementDao()
            val book = TestDataFactory.dataBooks().first()
            dao.insertAudiobooks(listOf(book))

            dao.insertBookmark(BookmarkEntity(bookId = book.id, chapterIndex = 0,
                chapterTitle = "Розділ", timestampSeconds = 1L, note = "справжня нотатка"))
            dao.insertBookmark(BookmarkEntity(bookId = book.id, chapterIndex = 1,
                chapterTitle = "Розділ", timestampSeconds = 2L, note = ""))
            dao.insertBookmark(BookmarkEntity(bookId = book.id, chapterIndex = 2,
                chapterTitle = "Розділ", timestampSeconds = 3L, note = "   "))

            assertEquals("закладок мусить бути три", 3L, achievementDao.observeBookmarks().first())
            assertEquals("нотатка мусить бути рівно одна", 1L, achievementDao.observeNotes().first())
        } finally {
            database.close()
        }
    }
}
