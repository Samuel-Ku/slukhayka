package com.slukhayka.audiobooks.data.merge

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.LibraryEntryEntity
import com.slukhayka.audiobooks.data.db.WorkEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ManualWorkMergeStoragePrecisionRoomTest {
    @Test(timeout = 90_000)
    fun `prepare distinguishes SQL REAL values that narrow to the same typed Float`() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val db = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = db.audiobookDao()
            val fixture = Fixture()
            fixture.seed(dao)
            fixture.assertStored(dao)

            // Exactly two setup UPDATEs of A.rating; B/C and all other columns are untouched.
            db.openHelper.writableDatabase.compileStatement(
                "UPDATE audiobooks SET rating = 0.1 WHERE id = 'storage-book-a'",
            ).use { assertEquals(1, it.executeUpdateDelete()) }
            assertRatingStorage(db, 0x3fb999999999999aL)
            fixture.assertStored(dao)
            val merge = ManualWorkMerge(dao)
            val first = merge.prepareManualWorkMerge("storage-work-a", "storage-work-b")
            fixture.assertPreview(first)
            fixture.assertStored(dao)
            assertTrue(first.fingerprint.matches(Regex("manual-work-merge-v1:[0-9a-f]{64}")))
            assertTrue(first.storageFingerprint.matches(Regex("manual-work-merge-storage-v2:[0-9a-f]{64}")))

            db.openHelper.writableDatabase.compileStatement(
                "UPDATE audiobooks SET rating = 0.10000000000000002 WHERE id = 'storage-book-a'",
            ).use { assertEquals(1, it.executeUpdateDelete()) }
            assertRatingStorage(db, 0x3fb999999999999bL)
            fixture.assertStored(dao)
            val second = merge.prepareManualWorkMerge("storage-work-a", "storage-work-b")
            fixture.assertPreview(second)
            fixture.assertStored(dao)
            assertTrue(second.storageFingerprint.matches(Regex("manual-work-merge-storage-v2:[0-9a-f]{64}")))
            assertEquals(first.fingerprint, second.fingerprint)
            assertEquals(first.snapshot.rootWorkIds, second.snapshot.rootWorkIds)
            assertEquals(first.participants, second.participants)
            assertEquals(first.counts, second.counts)
            assertEquals(first.conflicts, second.conflicts)
            assertEquals(first.snapshot.library.books, second.snapshot.library.books)
            assertEquals(0x3dcccccd, first.snapshot.library.books.single { it.id == "storage-book-a" }.rating.toRawBits())
            assertEquals(0x3dcccccd, second.snapshot.library.books.single { it.id == "storage-book-a" }.rating.toRawBits())
            assertNotEquals(
                "Distinct stored binary64 ratings must invalidate the storage fingerprint even when typed Float is unchanged",
                first.storageFingerprint,
                second.storageFingerprint,
            )
        } finally {
            db.close()
        }
    }

    // The sole raw read oracle is A.rating's SQLite type and binary64 payload.
    private fun assertRatingStorage(db: AudiobookDatabase, expectedBits: Long) {
        db.openHelper.readableDatabase.query(
            "SELECT typeof(rating), rating FROM audiobooks WHERE id = 'storage-book-a'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("real", cursor.getString(0))
            assertEquals(expectedBits, cursor.getDouble(1).toRawBits())
            assertFalse(cursor.moveToNext())
        }
    }

    private class Fixture {
        val workA = WorkEntity(
            id = "storage-work-a", mergeKey = "storage a|author a", title = "Storage A", author = "Author A",
            seriesTitle = null, seriesUrl = null, seriesIndex = null, coverImageUrl = null, addedAt = 10L,
        )
        val workB = WorkEntity(
            id = "storage-work-b", mergeKey = "storage b|author b", title = "Storage B", author = "Author B",
            seriesTitle = null, seriesUrl = null, seriesIndex = null, coverImageUrl = null, addedAt = 20L,
        )
        val workC = WorkEntity(
            id = "storage-work-c", mergeKey = "storage c|author c", title = "Storage C", author = "Author C",
            seriesTitle = null, seriesUrl = null, seriesIndex = null, coverImageUrl = null, addedAt = 30L,
        )
        val entryA = LibraryEntryEntity(
            id = "storage-book-a", workId = "storage-work-a", origin = "UNKNOWN", isFavorite = true,
            createdAt = 11L, downloadProgress = 0f, downloadState = "IDLE",
        )
        val entryB = LibraryEntryEntity(
            id = "storage-book-b", workId = "storage-work-b", origin = "UNKNOWN", isFavorite = false,
            createdAt = 21L, downloadProgress = 0.25f, downloadState = "IDLE",
        )
        val entryC = LibraryEntryEntity(
            id = "storage-book-c", workId = "storage-work-c", origin = "UNKNOWN", isFavorite = true,
            createdAt = 31L, downloadProgress = 0.5f, downloadState = "IDLE",
        )
        val bookA = ManualWorkMergeBookRow(
            id = "storage-book-a", title = "Library A", author = "Author A", narrator = "Narrator A",
            description = "Raw A", coverDrawableRes = 0, coverImageUrl = null, genre = "",
            sourceUrl = "https://example.test/storage/A", isDownloaded = false,
            totalDurationSeconds = 0L, totalChapters = 0, rating = 0.1f, sourceTreeUri = null,
        )
        val bookB = ManualWorkMergeBookRow(
            id = "storage-book-b", title = "Library B", author = "Author B", narrator = "Narrator B",
            description = "Raw B", coverDrawableRes = 0, coverImageUrl = null, genre = "",
            sourceUrl = "https://example.test/storage/B", isDownloaded = false,
            totalDurationSeconds = 0L, totalChapters = 0, rating = 0.5f, sourceTreeUri = null,
        )
        val bookC = ManualWorkMergeBookRow(
            id = "storage-book-c", title = "Library C", author = "Author C", narrator = "Narrator C",
            description = "Raw C", coverDrawableRes = 0, coverImageUrl = null, genre = "",
            sourceUrl = "https://example.test/storage/C", isDownloaded = false,
            totalDurationSeconds = 0L, totalChapters = 0, rating = 0.75f, sourceTreeUri = null,
        )

        suspend fun seed(dao: AudiobookDao) {
            dao.upsertWork(workA)
            dao.upsertWork(workB)
            dao.upsertWork(workC)
            dao.insertAudiobooks(listOf(
                AudiobookEntity(
                    id = "storage-book-a", title = "Library A", author = "Author A", narrator = "Narrator A",
                    description = "Raw A", coverDrawableRes = 0, coverImageUrl = null, genre = "",
                    sourceUrl = "https://example.test/storage/A", isDownloaded = false,
                    totalDurationSeconds = 0L, totalChapters = 0, rating = 0.1f, sourceTreeUri = null,
                ),
                AudiobookEntity(
                    id = "storage-book-b", title = "Library B", author = "Author B", narrator = "Narrator B",
                    description = "Raw B", coverDrawableRes = 0, coverImageUrl = null, genre = "",
                    sourceUrl = "https://example.test/storage/B", isDownloaded = false,
                    totalDurationSeconds = 0L, totalChapters = 0, rating = 0.5f, sourceTreeUri = null,
                ),
                AudiobookEntity(
                    id = "storage-book-c", title = "Library C", author = "Author C", narrator = "Narrator C",
                    description = "Raw C", coverDrawableRes = 0, coverImageUrl = null, genre = "",
                    sourceUrl = "https://example.test/storage/C", isDownloaded = false,
                    totalDurationSeconds = 0L, totalChapters = 0, rating = 0.75f, sourceTreeUri = null,
                ),
            ))
            dao.upsertLibraryEntry("storage-book-a", "storage-work-a", true, 11L, 0f, "IDLE")
            dao.upsertLibraryEntry("storage-book-b", "storage-work-b", false, 21L, 0.25f, "IDLE")
            dao.upsertLibraryEntry("storage-book-c", "storage-work-c", true, 31L, 0.5f, "IDLE")
        }

        suspend fun assertStored(dao: AudiobookDao) {
            assertEquals(workA, dao.getWorkById("storage-work-a"))
            assertEquals(workB, dao.getWorkById("storage-work-b"))
            assertEquals(workC, dao.getWorkById("storage-work-c"))
            assertEquals(entryA, dao.libraryEntryById("storage-book-a"))
            assertEquals(entryB, dao.libraryEntryById("storage-book-b"))
            assertEquals(entryC, dao.libraryEntryById("storage-book-c"))
            assertEquals(
                listOf(bookA, bookB, bookC),
                dao.manualMergeBooks(listOf("storage-book-a", "storage-book-b", "storage-book-c")).sortedBy { it.id },
            )
        }

        fun assertPreview(preview: ManualWorkMergePreview) {
            assertEquals(listOf("storage-work-a", "storage-work-b"), preview.snapshot.rootWorkIds)
            assertEquals(listOf("storage-work-a", "storage-work-b"), preview.participants.map { it.work.id })
            assertEquals(workA, preview.participants[0].work)
            assertEquals(workB, preview.participants[1].work)
            assertEquals(emptyList<ManualWorkMergeEditionPreview>(), preview.participants[0].editions)
            assertEquals(emptyList<ManualWorkMergeEditionPreview>(), preview.participants[1].editions)
            assertEquals(emptyList<ManualWorkMergeConflict>(), preview.conflicts)
            assertEquals(emptyList<ManualWorkMergeConflict>(), preview.snapshot.conflicts)
            assertEquals(listOf(workA, workB), preview.snapshot.library.works.sortedBy { it.id })
            assertEquals(listOf(entryA, entryB), preview.snapshot.library.entries.sortedBy { it.id })
            assertEquals(listOf(bookA, bookB), preview.snapshot.library.books.sortedBy { it.id })
            assertEquals(
                ManualWorkMergeCounts(
                    works = 2,
                    entries = 2,
                    books = 2,
                    editions = 0,
                    sources = 0,
                    tracks = 0,
                    chapters = 0,
                    progress = 0,
                    bookmarks = 0,
                    settings = 0,
                    readthroughs = 0,
                    events = 0,
                    failures = 0,
                    submissions = 0,
                    workSources = 0,
                    series = 0,
                    seriesMembers = 0,
                    universes = 0,
                    workFacets = 0,
                    facetSeries = 0,
                    genres = 0,
                    genreStates = 0,
                    genreAssertions = 0,
                    workGenres = 0,
                    editionFacets = 0,
                    authors = 0,
                    authorAliases = 0,
                    ftsRows = 0,
                    popularity = 0,
                    vectors = 0,
                    corrections = 0,
                    tombstones = 0,
                    preferences = 0,
                    collections = 0,
                    collectionItems = 0,
                    feedSnapshots = 0,
                    retainedStats = 0,
                    retainedSessions = 0,
                    retainedAchievements = 0,
                    retainedAchievementFacts = 0,
                    retainedAchievementCounters = 0,
                    retainedPersonBookmarks = 0,
                    retainedHiddenReviewers = 0,
                    retainedFriendships = 0,
                    retainedSocialBlocks = 0,
                ),
                preview.counts,
            )
            assertEmptyChildren(preview.snapshot)
        }

        fun assertEmptyChildren(snapshot: ManualWorkMergeSnapshot) {
            assertEquals(emptyList<Any>(), snapshot.personal.corrections)
            assertEquals(emptyList<Any>(), snapshot.library.editions)
            assertEquals(emptyList<Any>(), snapshot.library.sources)
            assertEquals(emptyList<Any>(), snapshot.library.tracks)
            assertEquals(emptyList<Any>(), snapshot.library.chapters)
            assertEquals(emptyList<Any>(), snapshot.library.progress)
            assertEquals(emptyList<Any>(), snapshot.library.bookmarks)
            assertEquals(emptyList<Any>(), snapshot.library.settings)
            assertEquals(emptyList<Any>(), snapshot.library.readthroughs)
            assertEquals(emptyList<Any>(), snapshot.library.events)
            assertEquals(emptyList<Any>(), snapshot.library.failures)
            assertEquals(emptyList<Any>(), snapshot.library.submissions)
            assertEquals(emptyList<Any>(), snapshot.catalog.workSources)
            assertEquals(emptyList<Any>(), snapshot.catalog.series)
            assertEquals(emptyList<Any>(), snapshot.catalog.seriesMembers)
            assertEquals(emptyList<Any>(), snapshot.catalog.universes)
            assertEquals(emptyList<Any>(), snapshot.catalog.workFacets)
            assertEquals(emptyList<Any>(), snapshot.catalog.facetSeries)
            assertEquals(emptyList<Any>(), snapshot.catalog.genres)
            assertEquals(emptyList<Any>(), snapshot.catalog.genreStates)
            assertEquals(emptyList<Any>(), snapshot.catalog.genreAssertions)
            assertEquals(emptyList<Any>(), snapshot.catalog.workGenres)
            assertEquals(emptyList<Any>(), snapshot.catalog.editionFacets)
            assertEquals(emptyList<Any>(), snapshot.catalog.authors)
            assertEquals(emptyList<Any>(), snapshot.catalog.authorAliases)
            assertEquals(emptyList<Any>(), snapshot.catalog.ftsRows)
            assertEquals(emptyList<Any>(), snapshot.catalog.popularity)
            assertEquals(emptyList<Any>(), snapshot.catalog.vectors)
            assertEquals(emptyList<Any>(), snapshot.personal.tombstones)
            assertEquals(emptyList<Any>(), snapshot.personal.preferences)
            assertEquals(emptyList<Any>(), snapshot.personal.collections)
            assertEquals(emptyList<Any>(), snapshot.personal.collectionItems)
            assertEquals(emptyList<Any>(), snapshot.personal.feedSnapshots)
            assertEquals(emptyList<Any>(), snapshot.retained.stats)
            assertEquals(emptyList<Any>(), snapshot.retained.sessions)
            assertEquals(emptyList<Any>(), snapshot.retained.achievements)
            assertEquals(emptyList<Any>(), snapshot.retained.achievementFacts)
            assertEquals(emptyList<Any>(), snapshot.retained.achievementCounters)
            assertEquals(emptyList<Any>(), snapshot.retained.personBookmarks)
            assertEquals(emptyList<Any>(), snapshot.retained.hiddenReviewers)
            assertEquals(emptyList<Any>(), snapshot.retained.friendships)
            assertEquals(emptyList<Any>(), snapshot.retained.socialBlocks)
        }
    }
}
