package com.slukhayka.audiobooks.data.merge

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.CorrectionEntity
import com.slukhayka.audiobooks.data.db.LibraryEntryEntity
import com.slukhayka.audiobooks.data.db.WorkEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ManualWorkMergePairRulesRoomTest {
    @Test(timeout = 90_000)
    fun `prepare blocks only the exact pair and retains third library copy evidence`() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val db = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = db.audiobookDao()
            val fixture = Fixture()
            fixture.seed(dao)
            fixture.assertStored(dao)

            val preview = ManualWorkMerge(dao)
                .prepareManualWorkMerge("local-folder:pair-A", "chapter-order:pair-B")

            assertEquals(listOf("chapter-order:pair-B", "local-folder:pair-A"), preview.snapshot.rootWorkIds)
            assertEquals(listOf("local-folder:pair-A", "chapter-order:pair-B"), preview.participants.map { it.work.id })
            assertEquals(fixture.workA, preview.participants[0].work)
            assertEquals(fixture.workB, preview.participants[1].work)
            assertEquals(emptyList<ManualWorkMergeEditionPreview>(), preview.participants[0].editions)
            assertEquals(emptyList<ManualWorkMergeEditionPreview>(), preview.participants[1].editions)

            // A/Entry-B forbids these two Works; B/Entry-X does not forbid A/B.
            val expectedConflicts = listOf<ManualWorkMergeConflict>(ManualWorkMergeConflict.NeverMatch(fixture.ruleP))
            assertEquals("Only the exact requested pair may be a NEVER_MATCH blocker", expectedConflicts, preview.conflicts)
            assertEquals(expectedConflicts, preview.snapshot.conflicts)
            assertEquals(
                listOf(fixture.ruleP, fixture.ruleT),
                preview.snapshot.personal.corrections.sortedWith(compareBy<CorrectionEntity> { it.mergeKey }
                    .thenBy { it.kind }.thenBy { it.value }),
            )
            assertEquals(
                listOf(fixture.workB, fixture.workA, fixture.workX),
                preview.snapshot.library.works.sortedBy { it.id },
            )
            assertEquals(
                listOf(fixture.entryA, fixture.entryB, fixture.entryX),
                preview.snapshot.library.entries.sortedBy { it.id },
            )
            assertEquals(
                "The existing Entry X must retain its complete legacy Book carrier",
                listOf(fixture.bookA, fixture.bookB, fixture.bookX),
                preview.snapshot.library.books.sortedBy { it.id },
            )
            assertEquals(
                ManualWorkMergeCounts(
                    works = 3,
                    entries = 3,
                    books = 3,
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
                    corrections = 2,
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
            fixture.assertEmptyChildren(preview.snapshot)
            // The unrelated raw U row is still stored, and X remains evidence, not a participant.
            fixture.assertStored(dao)
        } finally {
            db.close()
        }
    }

    private class Fixture {
        val workA = WorkEntity(
            id = "local-folder:pair-A", mergeKey = "pair a|author a", title = "Pair A", author = "Author A",
            seriesTitle = null, seriesUrl = null, seriesIndex = null, coverImageUrl = null, addedAt = 10L,
        )
        val workB = WorkEntity(
            id = "chapter-order:pair-B", mergeKey = "pair b|author b", title = "Pair B", author = "Author B",
            seriesTitle = null, seriesUrl = null, seriesIndex = null, coverImageUrl = null, addedAt = 20L,
        )
        val workX = WorkEntity(
            id = "pair-X", mergeKey = "pair x|author x", title = "Pair X", author = "Author X",
            seriesTitle = null, seriesUrl = null, seriesIndex = null, coverImageUrl = null, addedAt = 30L,
        )
        val entryA = LibraryEntryEntity(
            id = "pair-book-A", workId = "local-folder:pair-A", origin = "UNKNOWN", isFavorite = true,
            createdAt = 11L, downloadProgress = 0f, downloadState = "IDLE",
        )
        val entryB = LibraryEntryEntity(
            id = "pair-book-B", workId = "chapter-order:pair-B", origin = "UNKNOWN", isFavorite = false,
            createdAt = 21L, downloadProgress = 0.25f, downloadState = "IDLE",
        )
        val entryX = LibraryEntryEntity(
            id = "pair-book-X", workId = "pair-X", origin = "UNKNOWN", isFavorite = true,
            createdAt = 31L, downloadProgress = 0.5f, downloadState = "IDLE",
        )
        val bookA = ManualWorkMergeBookRow(
            id = "pair-book-A", title = "Library A", author = "Author A", narrator = "Narrator A",
            description = "Raw A", coverDrawableRes = 0, coverImageUrl = null, genre = "",
            sourceUrl = "https://example.test/pair/A", isDownloaded = false,
            totalDurationSeconds = 0L, totalChapters = 0, rating = 0f, sourceTreeUri = null,
        )
        val bookB = ManualWorkMergeBookRow(
            id = "pair-book-B", title = "Library B", author = "Author B", narrator = "Narrator B",
            description = "Raw B", coverDrawableRes = 0, coverImageUrl = null, genre = "",
            sourceUrl = "https://example.test/pair/B", isDownloaded = false,
            totalDurationSeconds = 0L, totalChapters = 0, rating = 0f, sourceTreeUri = null,
        )
        val bookX = ManualWorkMergeBookRow(
            id = "pair-book-X", title = "Library X", author = "Author X", narrator = "Narrator X",
            description = "Raw X", coverDrawableRes = 0, coverImageUrl = null, genre = "",
            sourceUrl = "https://example.test/pair/X", isDownloaded = false,
            totalDurationSeconds = 0L, totalChapters = 0, rating = 0f, sourceTreeUri = null,
        )
        val ruleP = CorrectionEntity(
            mergeKey = "local-folder:pair-A", kind = "NEVER_MATCH", value = "pair-book-B",
            origin = "USER_MADE", updatedAt = 401L, updatedBy = "fixture-pair",
        )
        val ruleT = CorrectionEntity(
            mergeKey = "pair b|author b", kind = "NEVER_MATCH", value = "pair-book-X",
            origin = "USER_MADE", updatedAt = 402L, updatedBy = "fixture-pair",
        )
        val ruleU = CorrectionEntity(
            mergeKey = "local-folder:unrelated", kind = "NEVER_MATCH", value = "chapter-order:unrelated",
            origin = "USER_MADE", updatedAt = 403L, updatedBy = "fixture-pair",
        )

        suspend fun seed(dao: AudiobookDao) {
            dao.upsertWork(workA)
            dao.upsertWork(workB)
            dao.upsertWork(workX)
            dao.insertAudiobooks(listOf(
                AudiobookEntity(
                    id = "pair-book-A", title = "Library A", author = "Author A", narrator = "Narrator A",
                    description = "Raw A", coverDrawableRes = 0, coverImageUrl = null, genre = "",
                    sourceUrl = "https://example.test/pair/A", isDownloaded = false,
                    totalDurationSeconds = 0L, totalChapters = 0, rating = 0f, sourceTreeUri = null,
                ),
                AudiobookEntity(
                    id = "pair-book-B", title = "Library B", author = "Author B", narrator = "Narrator B",
                    description = "Raw B", coverDrawableRes = 0, coverImageUrl = null, genre = "",
                    sourceUrl = "https://example.test/pair/B", isDownloaded = false,
                    totalDurationSeconds = 0L, totalChapters = 0, rating = 0f, sourceTreeUri = null,
                ),
                AudiobookEntity(
                    id = "pair-book-X", title = "Library X", author = "Author X", narrator = "Narrator X",
                    description = "Raw X", coverDrawableRes = 0, coverImageUrl = null, genre = "",
                    sourceUrl = "https://example.test/pair/X", isDownloaded = false,
                    totalDurationSeconds = 0L, totalChapters = 0, rating = 0f, sourceTreeUri = null,
                ),
            ))
            dao.upsertLibraryEntry("pair-book-A", "local-folder:pair-A", true, 11L, 0f, "IDLE")
            dao.upsertLibraryEntry("pair-book-B", "chapter-order:pair-B", false, 21L, 0.25f, "IDLE")
            dao.upsertLibraryEntry("pair-book-X", "pair-X", true, 31L, 0.5f, "IDLE")
            dao.upsertCorrection(ruleP)
            dao.upsertCorrection(ruleT)
            dao.upsertCorrection(ruleU)
        }

        suspend fun assertStored(dao: AudiobookDao) {
            assertEquals(workA, dao.getWorkById("local-folder:pair-A"))
            assertEquals(workB, dao.getWorkById("chapter-order:pair-B"))
            assertEquals(workX, dao.getWorkById("pair-X"))
            assertEquals(entryA, dao.libraryEntryById("pair-book-A"))
            assertEquals(entryB, dao.libraryEntryById("pair-book-B"))
            assertEquals(entryX, dao.libraryEntryById("pair-book-X"))
            assertEquals(
                listOf(bookA, bookB, bookX),
                dao.manualMergeBooks(listOf("pair-book-A", "pair-book-B", "pair-book-X")).sortedBy { it.id },
            )
            assertEquals(listOf(ruleP), dao.getCorrectionsForMergeKey("local-folder:pair-A"))
            assertEquals(listOf(ruleT), dao.getCorrectionsForMergeKey("pair b|author b"))
            assertEquals(listOf(ruleU), dao.getCorrectionsForMergeKey("local-folder:unrelated"))
        }

        fun assertEmptyChildren(snapshot: ManualWorkMergeSnapshot) {
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
