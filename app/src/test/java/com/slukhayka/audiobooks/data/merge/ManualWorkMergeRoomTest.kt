package com.slukhayka.audiobooks.data.merge

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.BookmarkEntity
import com.slukhayka.audiobooks.data.db.ChapterEntity
import com.slukhayka.audiobooks.data.db.EditionEntity
import com.slukhayka.audiobooks.data.db.LibraryEntryEntity
import com.slukhayka.audiobooks.data.db.PlaybackProgressEntity
import com.slukhayka.audiobooks.data.db.WorkEntity
import com.slukhayka.audiobooks.data.entries.LibraryEntryOrigin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ManualWorkMergeRoomTest {
    @Test(timeout = 60_000)
    fun `equal display names produce a preview with separate editions and listening state without changing stored rows`() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val db = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = db.audiobookDao()
            val firstWork = WorkEntity(
                id = "імx27я тіні|айя нея",
                mergeKey = "імx27я тіні|айя нея",
                title = "Ім'я тіні",
                author = "Айя Нея",
                addedAt = 101L,
            )
            val secondWork = WorkEntity(
                id = "імя тіні|айя нея",
                mergeKey = "імя тіні|айя нея",
                title = "Ім'я тіні",
                author = "Айя Нея",
                addedAt = 202L,
            )
            val firstBook = AudiobookEntity(
                id = "book-uk",
                title = "Ім'я тіні",
                author = "Айя Нея",
                narrator = "Олена",
                description = "Перша начитка",
                coverDrawableRes = 0,
                genre = "Фентезі",
                sourceUrl = "",
                totalDurationSeconds = 1800L,
                totalChapters = 1,
                rating = 0f,
            )
            val secondBook = firstBook.copy(
                id = "book-en",
                narrator = "David",
                description = "Друга начитка",
                totalDurationSeconds = 3600L,
            )
            val firstEntry = LibraryEntryEntity(
                id = "book-uk",
                workId = "імx27я тіні|айя нея",
                origin = "EXPLICIT_IMPORT",
                createdAt = 303L,
            )
            val secondEntry = LibraryEntryEntity(
                id = "book-en",
                workId = "імя тіні|айя нея",
                origin = "EXPLICIT_IMPORT",
                createdAt = 404L,
            )
            val firstEdition = EditionEntity(
                id = "edition-uk",
                workId = "book-uk",
                language = "uk",
                narrator = "Олена",
                totalChapters = 1,
                totalDurationSeconds = 1800L,
                addedAt = 505L,
            )
            val secondEdition = EditionEntity(
                id = "edition-en",
                workId = "book-en",
                language = "en",
                narrator = "David",
                totalChapters = 1,
                totalDurationSeconds = 3600L,
                addedAt = 606L,
            )
            val firstChapter = ChapterEntity("chapter-uk", "book-uk", 0, "Розділ один", 1800L, "edition-uk")
            val secondChapter = ChapterEntity("chapter-en", "book-en", 0, "Chapter one", 3600L, "edition-en")
            val firstProgress = PlaybackProgressEntity(
                editionId = "edition-uk",
                bookId = "book-uk",
                currentPositionSeconds = 137L,
                lastListenedAt = 707L,
                lastPausedAtEpochMs = 700_001L,
                preferredSpeed = 1.25f,
            )
            val secondProgress = PlaybackProgressEntity(
                editionId = "edition-en",
                bookId = "book-en",
                currentPositionSeconds = 911L,
                lastListenedAt = 808L,
                lastPausedAtEpochMs = 800_002L,
                preferredSpeed = 1.75f,
            )
            val firstBookmark = BookmarkEntity(701L, "book-uk", "edition-uk", 0, "Розділ один", 83L, "Перша закладка", 909L)
            val secondBookmark = BookmarkEntity(702L, "book-en", "edition-en", 0, "Chapter one", 900L, "Друга закладка", 1010L)

            dao.insertAudiobooks(listOf(firstBook, secondBook))
            dao.upsertWork(firstWork)
            dao.upsertWork(secondWork)
            dao.insertLibraryEntryWithOrigin("book-uk", "імx27я тіні|айя нея", LibraryEntryOrigin.EXPLICIT_IMPORT.name, 303L)
            dao.insertLibraryEntryWithOrigin("book-en", "імя тіні|айя нея", LibraryEntryOrigin.EXPLICIT_IMPORT.name, 404L)
            dao.insertEdition(firstEdition)
            dao.insertEdition(secondEdition)
            dao.insertChapters(listOf(firstChapter, secondChapter))
            dao.savePlaybackProgress(firstProgress)
            dao.savePlaybackProgress(secondProgress)
            dao.insertBookmark(firstBookmark)
            dao.insertBookmark(secondBookmark)

            suspend fun assertStoredParticipantsUnchanged() {
                assertEquals(listOf(firstWork, secondWork), dao.observeWorks().first().sortedBy { it.id })
                assertEquals(listOf(secondEntry, firstEntry), dao.observeLibraryEntries().first().sortedBy { it.id })
                assertEquals(listOf(secondEdition, firstEdition), dao.observeEditions().first().sortedBy { it.id })
                assertEquals(listOf(firstChapter), dao.getChaptersListForBook("book-uk"))
                assertEquals(listOf(secondChapter), dao.getChaptersListForBook("book-en"))
                assertEquals(listOf(secondProgress, firstProgress), dao.getAllPlaybackProgress().first().sortedBy { it.editionId })
                assertEquals(listOf(firstBookmark, secondBookmark), dao.getAllBookmarks().first().sortedBy { it.id })
                val books = dao.getAllAudiobooksOnce().sortedBy { it.id }
                assertEquals(listOf("book-en", "book-uk"), books.map { it.id })
                assertEquals(listOf(secondBook, firstBook), books.map { it.toAudiobookEntity() })
            }
            assertStoredParticipantsUnchanged()

            val preview = ManualWorkMerge(dao).prepareManualWorkMerge(
                "імx27я тіні|айя нея",
                "імя тіні|айя нея",
            )

            assertEquals(2, preview.participants.size)
            assertEquals(setOf("імx27я тіні|айя нея", "імя тіні|айя нея"), preview.participants.map { it.work.id }.toSet())
            val first = preview.participants.single { it.work.id == "імx27я тіні|айя нея" }
            val second = preview.participants.single { it.work.id == "імя тіні|айя нея" }
            assertEquals(firstWork, first.work)
            assertEquals(secondWork, second.work)
            val ukrainian = first.editions.single()
            val english = second.editions.single()
            assertEquals("book-uk", ukrainian.bookId)
            assertEquals("book-en", english.bookId)
            assertEquals(firstEdition, ukrainian.edition)
            assertEquals(secondEdition, english.edition)
            assertEquals(listOf(firstChapter), ukrainian.chapters)
            assertEquals(listOf(secondChapter), english.chapters)
            assertEquals(firstProgress, ukrainian.progress)
            assertEquals(secondProgress, english.progress)
            assertEquals(listOf(firstBookmark), ukrainian.bookmarks)
            assertEquals(listOf(secondBookmark), english.bookmarks)

            assertStoredParticipantsUnchanged()
        } finally {
            db.close()
        }
    }

    @Test(timeout = 60_000)
    fun `preview includes distinct library and catalogue editions without creating a library association`() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val db = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = db.audiobookDao()
            val firstWork = WorkEntity(
                id = "імx27я тіні|айя нея",
                mergeKey = "імx27я тіні|айя нея",
                title = "Ім'я тіні",
                author = "Айя Нея",
                addedAt = 101L,
            )
            val secondWork = WorkEntity(
                id = "імя тіні|айя нея",
                mergeKey = "імя тіні|айя нея",
                title = "Ім'я тіні",
                author = "Айя Нея",
                addedAt = 202L,
            )
            val firstBook = AudiobookEntity(
                id = "book-uk",
                title = "Ім'я тіні",
                author = "Айя Нея",
                narrator = "Олена",
                description = "Перша начитка",
                coverDrawableRes = 0,
                genre = "Фентезі",
                sourceUrl = "",
                totalDurationSeconds = 1800L,
                totalChapters = 1,
                rating = 0f,
            )
            val secondBook = firstBook.copy(
                id = "book-en",
                narrator = "David",
                description = "Друга начитка",
                totalDurationSeconds = 3600L,
            )
            val firstEntry = LibraryEntryEntity(
                id = "book-uk",
                workId = "імx27я тіні|айя нея",
                origin = "EXPLICIT_IMPORT",
                createdAt = 303L,
            )
            val secondEntry = LibraryEntryEntity(
                id = "book-en",
                workId = "імя тіні|айя нея",
                origin = "EXPLICIT_IMPORT",
                createdAt = 404L,
            )
            val firstEdition = EditionEntity(
                id = "edition-uk",
                workId = "book-uk",
                language = "uk",
                narrator = "Олена",
                totalChapters = 1,
                totalDurationSeconds = 1800L,
                addedAt = 505L,
            )
            val secondEdition = EditionEntity(
                id = "edition-en",
                workId = "book-en",
                language = "en",
                narrator = "David",
                totalChapters = 1,
                totalDurationSeconds = 3600L,
                addedAt = 606L,
            )
            val catalogEdition = EditionEntity(
                id = "edition-catalog-uk",
                workId = "імx27я тіні|айя нея",
                language = "uk",
                narrator = "Олена",
                totalChapters = 0,
                totalDurationSeconds = 2400L,
                addedAt = 607L,
            )
            val firstChapter = ChapterEntity("chapter-uk", "book-uk", 0, "Розділ один", 1800L, "edition-uk")
            val secondChapter = ChapterEntity("chapter-en", "book-en", 0, "Chapter one", 3600L, "edition-en")
            val firstProgress = PlaybackProgressEntity(
                editionId = "edition-uk",
                bookId = "book-uk",
                currentPositionSeconds = 137L,
                lastListenedAt = 707L,
                lastPausedAtEpochMs = 700_001L,
                preferredSpeed = 1.25f,
            )
            val secondProgress = PlaybackProgressEntity(
                editionId = "edition-en",
                bookId = "book-en",
                currentPositionSeconds = 911L,
                lastListenedAt = 808L,
                lastPausedAtEpochMs = 800_002L,
                preferredSpeed = 1.75f,
            )
            val firstBookmark = BookmarkEntity(701L, "book-uk", "edition-uk", 0, "Розділ один", 83L, "Перша закладка", 909L)
            val secondBookmark = BookmarkEntity(702L, "book-en", "edition-en", 0, "Chapter one", 900L, "Друга закладка", 1010L)

            dao.insertAudiobooks(listOf(firstBook, secondBook))
            dao.upsertWork(firstWork)
            dao.upsertWork(secondWork)
            dao.insertLibraryEntryWithOrigin("book-uk", "імx27я тіні|айя нея", LibraryEntryOrigin.EXPLICIT_IMPORT.name, 303L)
            dao.insertLibraryEntryWithOrigin("book-en", "імя тіні|айя нея", LibraryEntryOrigin.EXPLICIT_IMPORT.name, 404L)
            dao.insertEdition(firstEdition)
            dao.insertEdition(secondEdition)
            dao.insertEdition(catalogEdition)
            dao.insertChapters(listOf(firstChapter, secondChapter))
            dao.savePlaybackProgress(firstProgress)
            dao.savePlaybackProgress(secondProgress)
            dao.insertBookmark(firstBookmark)
            dao.insertBookmark(secondBookmark)

            suspend fun assertStoredParticipantsUnchanged() {
                assertEquals(listOf(firstWork, secondWork), dao.observeWorks().first().sortedBy { it.id })
                assertEquals(listOf(secondEntry, firstEntry), dao.observeLibraryEntries().first().sortedBy { it.id })
                assertEquals(listOf(catalogEdition, secondEdition, firstEdition), dao.observeEditions().first().sortedBy { it.id })
                assertEquals(listOf(firstChapter), dao.getChaptersListForBook("book-uk"))
                assertEquals(listOf(secondChapter), dao.getChaptersListForBook("book-en"))
                assertEquals(listOf(secondProgress, firstProgress), dao.getAllPlaybackProgress().first().sortedBy { it.editionId })
                assertEquals(listOf(firstBookmark, secondBookmark), dao.getAllBookmarks().first().sortedBy { it.id })
                assertNull(dao.libraryEntryById("імx27я тіні|айя нея"))
                assertNull(dao.getAudiobookById("імx27я тіні|айя нея"))
                assertEquals(emptyList<ChapterEntity>(), dao.getChaptersListForEdition("edition-catalog-uk"))
                assertNull(dao.getPlaybackProgressSyncByEdition("edition-catalog-uk"))
                assertEquals(emptyList<BookmarkEntity>(), dao.getBookmarksForEdition("edition-catalog-uk").first())
                val books = dao.getAllAudiobooksOnce().sortedBy { it.id }
                assertEquals(listOf("book-en", "book-uk"), books.map { it.id })
                assertEquals(listOf(secondBook, firstBook), books.map { it.toAudiobookEntity() })
            }
            assertStoredParticipantsUnchanged()

            val preview = ManualWorkMerge(dao).prepareManualWorkMerge(
                "імx27я тіні|айя нея",
                "імя тіні|айя нея",
            )

            assertEquals(2, preview.participants.size)
            assertEquals(setOf("імx27я тіні|айя нея", "імя тіні|айя нея"), preview.participants.map { it.work.id }.toSet())
            val first = preview.participants.single { it.work.id == "імx27я тіні|айя нея" }
            val second = preview.participants.single { it.work.id == "імя тіні|айя нея" }
            assertEquals("Work A keeps both existing Edition IDs", 2, first.editions.size)
            assertEquals(setOf("edition-uk", "edition-catalog-uk"), first.editions.map { it.edition.id }.toSet())
            assertEquals(1, second.editions.size)
            assertEquals(setOf("edition-en"), second.editions.map { it.edition.id }.toSet())
            assertEquals(firstWork, first.work)
            assertEquals(secondWork, second.work)
            val ukrainian = first.editions.single { it.edition.id == "edition-uk" }
            val catalog = first.editions.single { it.edition.id == "edition-catalog-uk" }
            val english = second.editions.single()
            assertEquals("book-uk", ukrainian.bookId)
            assertEquals("book-en", english.bookId)
            assertEquals(firstEdition, ukrainian.edition)
            assertEquals(secondEdition, english.edition)
            assertEquals(listOf(firstChapter), ukrainian.chapters)
            assertEquals(listOf(secondChapter), english.chapters)
            assertEquals(firstProgress, ukrainian.progress)
            assertEquals(secondProgress, english.progress)
            assertEquals(listOf(firstBookmark), ukrainian.bookmarks)
            assertEquals(listOf(secondBookmark), english.bookmarks)
            assertEquals(catalogEdition, catalog.edition)
            assertNull(catalog.bookId)
            assertEquals(emptyList<ChapterEntity>(), catalog.chapters)
            assertNull(catalog.progress)
            assertEquals(emptyList<BookmarkEntity>(), catalog.bookmarks)

            assertStoredParticipantsUnchanged()
        } finally {
            db.close()
        }
    }
}
