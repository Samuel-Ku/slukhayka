package com.slukhayka.audiobooks.data.merge

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.EditionEntity
import com.slukhayka.audiobooks.data.db.LibraryEntryEntity
import com.slukhayka.audiobooks.data.db.SourceEntity
import com.slukhayka.audiobooks.data.db.SubmissionStateEntity
import com.slukhayka.audiobooks.data.db.WorkEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ManualWorkMergeSubmissionOwnerRoomTest {
    @Test(timeout = 90_000)
    fun `prepare retains third Work evidence for mismatched Source and submission owners`() = runBlocking {
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
                .prepareManualWorkMerge("inverse-work-A", "inverse-work-B")

            assertEquals(listOf("inverse-work-A", "inverse-work-B"), preview.snapshot.rootWorkIds)
            assertEquals(listOf("inverse-work-A", "inverse-work-B"), preview.participants.map { it.work.id })
            assertEquals(fixture.workA, preview.participants[0].work)
            assertEquals(fixture.workB, preview.participants[1].work)
            assertEquals(emptyList<ManualWorkMergeEditionPreview>(), preview.participants[0].editions)
            assertEquals(emptyList<ManualWorkMergeEditionPreview>(), preview.participants[1].editions)
            assertEquals(
                listOf(fixture.workA, fixture.workB, fixture.workC),
                preview.snapshot.library.works.sortedBy { it.id },
            )
            assertEquals(
                listOf(fixture.entryA, fixture.entryB, fixture.entryC),
                preview.snapshot.library.entries.sortedBy { it.id },
            )
            assertEquals(fixture.rawBooks, preview.snapshot.library.books.sortedBy { it.id })
            assertEquals(emptyList<EditionEntity>(), preview.snapshot.library.editions)
            assertEquals(listOf(fixture.sourceA), preview.snapshot.library.sources)
            assertEquals(listOf(fixture.submissionC), preview.snapshot.library.submissions)
            assertEquals(3, preview.counts.works)
            assertEquals(3, preview.counts.entries)
            assertEquals(3, preview.counts.books)
            assertEquals(0, preview.counts.editions)
            assertEquals(1, preview.counts.sources)
            assertEquals(1, preview.counts.submissions)
            val expectedConflicts = listOf(
                ManualWorkMergeConflict.AmbiguousAnchor(
                    "submission_states", "inverse-source-A", "inverse-work-A", "inverse-work-C",
                ),
            )
            assertEquals(expectedConflicts, preview.conflicts)
            assertEquals(expectedConflicts, preview.snapshot.conflicts)

            fixture.assertStored(dao)
        } finally {
            db.close()
        }
    }

    private class Fixture {
        val workA = WorkEntity(
            id = "inverse-work-A", mergeKey = "inverse-a|author-a", title = "Work A", author = "Author A",
            seriesTitle = null, seriesUrl = null, seriesIndex = null, coverImageUrl = null, addedAt = 10L,
        )
        val workB = WorkEntity(
            id = "inverse-work-B", mergeKey = "inverse-b|author-b", title = "Work B", author = "Author B",
            seriesTitle = null, seriesUrl = null, seriesIndex = null, coverImageUrl = null, addedAt = 20L,
        )
        val workC = WorkEntity(
            id = "inverse-work-C", mergeKey = "inverse-c|author-c", title = "Work C", author = "Author C",
            seriesTitle = null, seriesUrl = null, seriesIndex = null, coverImageUrl = null, addedAt = 30L,
        )
        val entryA = LibraryEntryEntity(
            id = "inverse-book-A", workId = "inverse-work-A", origin = "UNKNOWN", isFavorite = false,
            createdAt = 11L, downloadProgress = 0f, downloadState = "IDLE",
        )
        val entryB = LibraryEntryEntity(
            id = "inverse-book-B", workId = "inverse-work-B", origin = "UNKNOWN", isFavorite = false,
            createdAt = 21L, downloadProgress = 0f, downloadState = "IDLE",
        )
        val entryC = LibraryEntryEntity(
            id = "inverse-book-C", workId = "inverse-work-C", origin = "UNKNOWN", isFavorite = true,
            createdAt = 31L, downloadProgress = 0.25f, downloadState = "DOWNLOADING",
        )
        val sourceA = SourceEntity(
            id = "inverse-source-A", bookId = "inverse-book-A", editionId = null, type = "YOUTUBE",
            url = "https://example.invalid/source/A", streamOnly = true, addedAt = 40L, lastScanFingerprint = null,
        )
        val submissionC = SubmissionStateEntity(
            sourceId = "inverse-source-A", url = "https://example.invalid/submission/C", bookId = "inverse-book-C",
            metadataJson = "{\"literal\":\"inverse-C\"}", channelId = "literal-channel", state = "AWAITING_PLAY",
            reason = null, createdAt = 41L, updatedAt = 42L,
        )
        val rawBooks = listOf(
            ManualWorkMergeBookRow(
                id = "inverse-book-A", title = "Book A", author = "Author A", narrator = "Narrator A",
                description = "Raw description A", coverDrawableRes = 0, coverImageUrl = null, genre = "",
                sourceUrl = "https://example.invalid/book/A", isDownloaded = false,
                totalDurationSeconds = 0L, totalChapters = 0, rating = 4.25f, sourceTreeUri = null,
            ),
            ManualWorkMergeBookRow(
                id = "inverse-book-B", title = "Book B", author = "Author B", narrator = "Narrator B",
                description = "Raw description B", coverDrawableRes = 0, coverImageUrl = null, genre = "",
                sourceUrl = "https://example.invalid/book/B", isDownloaded = false,
                totalDurationSeconds = 0L, totalChapters = 0, rating = 4.25f, sourceTreeUri = null,
            ),
            ManualWorkMergeBookRow(
                id = "inverse-book-C", title = "Book C", author = "Author C", narrator = "Narrator C",
                description = "Raw description C", coverDrawableRes = 0, coverImageUrl = null, genre = "",
                sourceUrl = "https://example.invalid/book/C", isDownloaded = false,
                totalDurationSeconds = 0L, totalChapters = 0, rating = 4.25f, sourceTreeUri = null,
            ),
        )

        suspend fun seed(dao: AudiobookDao) {
            dao.upsertWork(workA)
            dao.upsertWork(workB)
            dao.upsertWork(workC)
            dao.insertAudiobooks(listOf(
                AudiobookEntity(
                    id = "inverse-book-A", title = "Book A", author = "Author A", narrator = "Narrator A",
                    description = "Raw description A", coverDrawableRes = 0, coverImageUrl = null, genre = "",
                    sourceUrl = "https://example.invalid/book/A", isDownloaded = false,
                    totalDurationSeconds = 0L, totalChapters = 0, rating = 4.25f, sourceTreeUri = null,
                ),
                AudiobookEntity(
                    id = "inverse-book-B", title = "Book B", author = "Author B", narrator = "Narrator B",
                    description = "Raw description B", coverDrawableRes = 0, coverImageUrl = null, genre = "",
                    sourceUrl = "https://example.invalid/book/B", isDownloaded = false,
                    totalDurationSeconds = 0L, totalChapters = 0, rating = 4.25f, sourceTreeUri = null,
                ),
                AudiobookEntity(
                    id = "inverse-book-C", title = "Book C", author = "Author C", narrator = "Narrator C",
                    description = "Raw description C", coverDrawableRes = 0, coverImageUrl = null, genre = "",
                    sourceUrl = "https://example.invalid/book/C", isDownloaded = false,
                    totalDurationSeconds = 0L, totalChapters = 0, rating = 4.25f, sourceTreeUri = null,
                ),
            ))
            dao.upsertLibraryEntry("inverse-book-A", "inverse-work-A", false, 11L, 0f, "IDLE")
            dao.upsertLibraryEntry("inverse-book-B", "inverse-work-B", false, 21L, 0f, "IDLE")
            dao.upsertLibraryEntry("inverse-book-C", "inverse-work-C", true, 31L, 0.25f, "DOWNLOADING")
            dao.insertSources(listOf(sourceA))
            dao.upsertSubmissionState(submissionC)
        }

        suspend fun assertStored(dao: AudiobookDao) {
            assertEquals(workA, dao.getWorkById("inverse-work-A"))
            assertEquals(workB, dao.getWorkById("inverse-work-B"))
            assertEquals(workC, dao.getWorkById("inverse-work-C"))
            assertEquals(entryA, dao.libraryEntryById("inverse-book-A"))
            assertEquals(entryB, dao.libraryEntryById("inverse-book-B"))
            assertEquals(entryC, dao.libraryEntryById("inverse-book-C"))
            assertEquals(
                rawBooks,
                dao.manualMergeBooks(listOf("inverse-book-A", "inverse-book-B", "inverse-book-C")).sortedBy { it.id },
            )
            assertEquals(sourceA, dao.getSourceById("inverse-source-A"))
            assertEquals(submissionC, dao.submissionStateBySourceId("inverse-source-A"))
        }
    }
}
