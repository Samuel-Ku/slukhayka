package com.slukhayka.audiobooks.data.merge

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ManualWorkMergeReadthroughsApplyUndoRoomTest {
    @Test(timeout = 180_000)
    fun `apply changes only Readthrough Work identity and durable undo restores every pass`() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "manual-work-merge-readthroughs-${UUID.randomUUID()}.db"
        assertFalse(context.getDatabasePath(name).exists())
        fun open(): AudiobookDatabase = Room.databaseBuilder(context, AudiobookDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()
        var database = open()
        try {
            val fixture = Fixture()
            fixture.seed(database.audiobookDao())
            fixture.assertStored(database.audiobookDao(), applied = false)
            assertIdentity(database, state = null)
            val commands = ManualWorkMergeCommands(database, ManualWorkMergeGate(database))
            val prepared = commands.prepareManualWorkMerge(A, B)
            fixture.assertPreview(prepared.preview)
            fixture.assertStored(database.audiobookDao(), applied = false)
            assertIdentity(database, state = null)
            val applied = commands.applyManualWorkMerge(
                ManualWorkMergeApprovedPlan(
                    prepared = prepared, operationId = OPERATION,
                    canonicalTitle = "Same work", canonicalAuthor = "Shared Author",
                ),
            )
            assertTrue(
                "Readthrough carriers must apply with only bibliographic Work identity changed",
                applied is ManualWorkMergeApplyResult.Applied,
            )
            val result = applied as ManualWorkMergeApplyResult.Applied
            assertEquals(OPERATION, result.operationId)
            assertEquals(K, result.canonicalWorkId)
            fixture.assertStored(database.audiobookDao(), applied = true)
            assertIdentity(database, WorkMergeOperationState.APPLIED)

            database.close()
            database = open()
            fixture.assertStored(database.audiobookDao(), applied = true)
            assertIdentity(database, WorkMergeOperationState.APPLIED)
            val reopened = ManualWorkMergeCommands(database, ManualWorkMergeGate(database))
            val undone = reopened.undoManualWorkMerge(OPERATION)
            assertTrue(undone is ManualWorkMergeUndoResult.Undone)
            assertEquals(OPERATION, (undone as ManualWorkMergeUndoResult.Undone).operationId)
            fixture.assertStored(database.audiobookDao(), applied = false)
            assertIdentity(database, WorkMergeOperationState.UNDONE)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    private suspend fun assertIdentity(database: AudiobookDatabase, state: WorkMergeOperationState?) {
        val identity = database.workIdentityDao()
        assertEquals(state, identity.operationStateById(OPERATION))
        assertEquals(if (state == null) 0 else 1, identity.operationCount())
        assertEquals(if (state == WorkMergeOperationState.APPLIED) 4 else 0, identity.redirectCount())
        assertEquals(
            if (state == null) emptyList<Triple<String, String, Boolean>>() else listOf(
                Triple("pair-book-a", "pair-edition-a", true),
                Triple("pair-book-b", "pair-edition-b", true),
            ),
            identity.preservedParticipants(OPERATION).map {
                Triple(it.bookId, it.editionId, it.autoMergeExcluded)
            }.sortedBy { it.first },
        )
        for ((namespace, oldKey) in listOf(
            "WORK_ID" to "pair-work-a", "WORK_ID" to "pair-work-b",
            "MERGE_KEY" to "pair legacy a|shared author", "MERGE_KEY" to "pair legacy b|shared author",
        )) {
            val redirect = identity.redirectByAlias(namespace, oldKey)
            if (state == WorkMergeOperationState.APPLIED) {
                assertTrue(redirect != null)
                assertEquals(namespace, redirect!!.namespace)
                assertEquals(oldKey, redirect.oldKey)
                assertEquals(K, redirect.canonicalWorkId)
                assertEquals(OPERATION, redirect.operationId)
            } else {
                assertNull(redirect)
            }
        }
    }

    private class Fixture {
        val workA = WorkEntity(
            id = "pair-work-a", mergeKey = "pair legacy a|shared author", title = "Same work",
            author = "Shared Author", seriesTitle = null, seriesUrl = null, seriesIndex = null,
            coverImageUrl = null, addedAt = 10L,
        )
        val workB = WorkEntity(
            id = "pair-work-b", mergeKey = "pair legacy b|shared author", title = "Same work",
            author = "Shared Author", seriesTitle = "Known cycle", seriesUrl = "https://example.test/series/known",
            seriesIndex = 3, coverImageUrl = "https://example.test/work/known-cover.jpg", addedAt = 30L,
        )
        val entryA = LibraryEntryEntity(
            id = "pair-book-a", workId = "pair-work-a", origin = "UNKNOWN", isFavorite = true, createdAt = 11L,
            downloadProgress = 0.0f, downloadState = "IDLE",
        )
        val entryB = LibraryEntryEntity(
            id = "pair-book-b", workId = "pair-work-b", origin = "UNKNOWN", isFavorite = false, createdAt = 31L,
            downloadProgress = 0.0f, downloadState = "IDLE",
        )
        val bookA = ManualWorkMergeBookRow(
            id = "pair-book-a", title = "Same work", author = "Shared Author", narrator = "Shared Narrator",
            description = "Independent A rendition", coverDrawableRes = 0, coverImageUrl = null,
            genre = "Adventure", sourceUrl = "https://example.test/apply/A", isDownloaded = false,
            totalDurationSeconds = 120L, totalChapters = 1, rating = 0.25f, sourceTreeUri = null,
        )
        val bookB = ManualWorkMergeBookRow(
            id = "pair-book-b", title = "Same work", author = "Shared Author", narrator = "Shared Narrator",
            description = "Independent B rendition", coverDrawableRes = 0, coverImageUrl = null,
            genre = "Adventure", sourceUrl = "https://example.test/apply/A", isDownloaded = false,
            totalDurationSeconds = 120L, totalChapters = 1, rating = 0.5f, sourceTreeUri = null,
        )
        val editionA = EditionEntity(
            id = "pair-edition-a", workId = "pair-book-a", language = "uk", narrator = "Shared Narrator",
            totalChapters = 1, totalDurationSeconds = 120L, addedAt = 12L,
        )
        val editionB = EditionEntity(
            id = "pair-edition-b", workId = "pair-book-b", language = "uk", narrator = "Shared Narrator",
            totalChapters = 1, totalDurationSeconds = 120L, addedAt = 32L,
        )
        val progressA = PlaybackProgressEntity(
            editionId = "pair-edition-a", bookId = "pair-book-a", currentChapterIndex = 0,
            currentPositionSeconds = 17L, lastListenedAt = 1000L, isCompleted = false, lastPausedAtEpochMs = 1100L,
            preferredSpeed = 1.25f,
        )
        val progressB = PlaybackProgressEntity(
            editionId = "pair-edition-b", bookId = "pair-book-b", currentChapterIndex = 0,
            currentPositionSeconds = 80L, lastListenedAt = 2000L, isCompleted = false, lastPausedAtEpochMs = 2100L,
            preferredSpeed = 1.5f,
        )
        val bookmarkA = BookmarkEntity(
            id = 303L, bookId = "pair-book-a", editionId = "pair-edition-a", chapterIndex = 0,
            chapterTitle = "One chapter", timestampSeconds = 9L, note = "A bookmark", createdAt = 1010L,
        )
        val bookmarkB = BookmarkEntity(
            id = 404L, bookId = "pair-book-b", editionId = "pair-edition-b", chapterIndex = 0,
            chapterTitle = "One chapter", timestampSeconds = 80L, note = "B bookmark", createdAt = 2020L,
        )
        val chapterA = ChapterEntity(
            id = "pair-chapter-a", bookId = "pair-book-a", chapterIndex = 0, title = "One chapter",
            durationSeconds = 120L, editionId = "pair-edition-a",
        )
        val chapterB = ChapterEntity(
            id = "pair-chapter-b", bookId = "pair-book-b", chapterIndex = 0, title = "One chapter",
            durationSeconds = 120L, editionId = "pair-edition-b",
        )
        val sourceA = SourceEntity(
            id = "librivox-pair-edition-a", bookId = "pair-book-a", editionId = "pair-edition-a",
            type = "librivox", url = "https://example.test/apply/A", streamOnly = false, addedAt = 12L,
            lastScanFingerprint = null,
        )
        val sourceB = SourceEntity(
            id = "librivox-pair-edition-b", bookId = "pair-book-b", editionId = "pair-edition-b",
            type = "librivox", url = "https://example.test/apply/A", streamOnly = false, addedAt = 32L,
            lastScanFingerprint = null,
        )
        val trackA = SourceTrackEntity(
            id = "librivox-pair-edition-a_tr_1", sourceId = "librivox-pair-edition-a", trackIndex = 0,
            url = "https://example.test/apply/A/1.mp3", localFilePath = null, contentHash = null,
            isDownloaded = false,
        )
        val trackB = SourceTrackEntity(
            id = "librivox-pair-edition-b_tr_1", sourceId = "librivox-pair-edition-b", trackIndex = 0,
            url = "https://example.test/apply/A/1.mp3", localFilePath = null, contentHash = null,
            isDownloaded = false,
        )
        val claimA = WorkSourceEntity(
            id = "pair-work-a|librivox|b85c98b6", workId = "pair-work-a", sourceId = "librivox",
            sourceUrl = "https://example.test/apply/A", streamOnly = false,
            coverImageUrl = "https://example.test/apply/shared-cover.jpg", durationSeconds = 120L, addedAt = 40L,
        )
        val claimB = WorkSourceEntity(
            id = "pair-work-b|librivox|b85c98b6", workId = "pair-work-b", sourceId = "librivox",
            sourceUrl = "https://example.test/apply/A", streamOnly = false,
            coverImageUrl = "https://example.test/apply/shared-cover.jpg", durationSeconds = 120L, addedAt = 20L,
        )
        val ftsA = WorkSearchFtsEntity(
            workId = "pair-work-a", title = "same work", author = "shared author", series = "",
            narrator = "shared narrator",
        )
        val ftsB = WorkSearchFtsEntity(
            workId = "pair-work-b", title = "same work", author = "shared author", series = "known cycle",
            narrator = "shared narrator",
        )
        val canonicalWork = WorkEntity(
            id = "same work|shared author", mergeKey = "same work|shared author", title = "Same work",
            author = "Shared Author", seriesTitle = "Known cycle", seriesUrl = "https://example.test/series/known",
            seriesIndex = 3, coverImageUrl = "https://example.test/work/known-cover.jpg", addedAt = 10L,
        )
        val canonicalClaim = WorkSourceEntity(
            id = "same work|shared author|librivox|b85c98b6", workId = "same work|shared author",
            sourceId = "librivox", sourceUrl = "https://example.test/apply/A", streamOnly = false,
            coverImageUrl = "https://example.test/apply/shared-cover.jpg", durationSeconds = 120L, addedAt = 20L,
        )
        val canonicalFts = WorkSearchFtsEntity(
            workId = "same work|shared author", title = "same work", author = "shared author", series = "known cycle",
            narrator = "shared narrator",
        )

        val workC = WorkEntity(
            id = "unrelated-work-c", mergeKey = "unrelated work|other author", title = "Unrelated work",
            author = "Other Author", seriesTitle = null, seriesUrl = null, seriesIndex = null,
            coverImageUrl = null, addedAt = 8_000L,
        )
        val entryC = LibraryEntryEntity(
            id = "unrelated-entry-c", workId = "unrelated-work-c", origin = "UNKNOWN", isFavorite = false,
            createdAt = 8_001L, downloadProgress = 0.0f, downloadState = "IDLE",
        )
        val audioInProgressA = ReadthroughEntity(
            id = "pass-a-audio-current", libraryEntryId = "pair-book-a", workId = "pair-work-a",
            format = "AUDIO", state = "IN_PROGRESS", startedAt = 100L, finishedAt = null,
            editionId = "pair-edition-a", unit = "SECONDS", unitValue = 17,
            journalJson = """[ {"at":101,"u":"SECONDS","v":17}, {"futureTail":{"unit":"CHAPTER_FRACTION","marker":"А — original"}} ]""",
        )
        val audioFinishedA = ReadthroughEntity(
            id = "pass-a-audio-finished", libraryEntryId = "pair-book-a", workId = "pair-work-a",
            format = "AUDIO", state = "FINISHED", startedAt = 10L, finishedAt = 90L,
            editionId = "pair-edition-a", unit = "SECONDS", unitValue = 120,
            journalJson = """[ {"at":11,"u":"SECONDS","v":9}, {"at":90,"u":"SECONDS","v":120}, {"futureTail":{"opaque":true}} ]""",
        )
        val audioAbandonedB = ReadthroughEntity(
            id = "pass-b-audio-abandoned", libraryEntryId = "pair-book-b", workId = "pair-work-b",
            format = "AUDIO", state = "ABANDONED", startedAt = 200L, finishedAt = null,
            editionId = "pair-edition-b", unit = "SECONDS", unitValue = 80,
            journalJson = """[{"at":201,"u":"SECONDS","v":80}, {"futureTail":{"pause":"leave untouched"}}]""",
        )
        val paperB = ReadthroughEntity(
            id = "pass-b-paper", libraryEntryId = "pair-book-b", workId = "pair-work-b",
            format = "PAPER", state = "IN_PROGRESS", startedAt = 300L, finishedAt = null,
            editionId = null, unit = "PAGES", unitValue = 80,
            journalJson = """[ {"at":301,"u":"PAGES","v":20}, {"at":302,"u":"PAGES","v":80}, {"futureTail":{"paper":"own units"}} ]""",
        )
        val ebookB = ReadthroughEntity(
            id = "pass-b-ebook", libraryEntryId = "pair-book-b", workId = "pair-work-b",
            format = "EBOOK", state = "FINISHED", startedAt = 400L, finishedAt = 500L,
            editionId = null, unit = "PERCENT", unitValue = 100,
            journalJson = """[{"at":401,"u":"PERCENT","v":42}, {"at":500,"u":"PERCENT","v":100}, {"futureTail":{"layout":"unknown future"}}]""",
        )
        val paperC = ReadthroughEntity(
            id = "unrelated-pass-c", libraryEntryId = "unrelated-entry-c", workId = "unrelated-work-c",
            format = "PAPER", state = "ABANDONED", startedAt = 8_010L, finishedAt = null,
            editionId = null, unit = "PAGES", unitValue = 27,
            journalJson = """[ {"at":8011,"u":"PAGES","v":27}, {"futureTail":{"sentinel":"C stays exact"}} ]""",
        )
        val originalPasses = listOf(audioInProgressA, audioFinishedA, audioAbandonedB, paperB, ebookB)
        val canonicalPasses = listOf(
            audioInProgressA.copy(workId = "same work|shared author"),
            audioFinishedA.copy(workId = "same work|shared author"),
            audioAbandonedB.copy(workId = "same work|shared author"),
            paperB.copy(workId = "same work|shared author"),
            ebookB.copy(workId = "same work|shared author"),
        )

        val beforeCounts = mapOf(
            "achievements" to 0,
            "achievement_facts" to 0,
            "achievement_counters" to 0,
            "audiobooks" to 2,
            "listener_collections" to 0,
            "listener_collection_items" to 0,
            "sources" to 2,
            "chapters" to 2,
            "source_tracks" to 2,
            "bookmarks" to 2,
            "playback_progress" to 2,
            "listening_stats" to 0,
            "playback_sessions" to 0,
            "playback_failures" to 0,
            "playback_events" to 0,
            "tombstones" to 0,
            "corrections" to 0,
            "series" to 0,
            "series_members" to 0,
            "universes" to 0,
            "edition_settings" to 0,
            "works" to 2,
            "editions" to 2,
            "work_sources" to 2,
            "library_entries" to 2,
            "hidden_reviewers" to 0,
            "recommendation_preferences" to 0,
            "work_facets" to 0,
            "works_fts" to 2,
            "work_facet_series" to 0,
            "genre_facets" to 0,
            "work_genres" to 0,
            "genre_assertion_states" to 0,
            "genre_assertions" to 0,
            "edition_facets" to 0,
            "author_facets" to 0,
            "author_aliases" to 0,
            "person_bookmarks" to 0,
            "feed_snapshots" to 0,
            "popularity_assertions" to 0,
            "embedding_vectors" to 0,
            "submission_states" to 0,
            "readthroughs" to 5,
            "friendship_states" to 0,
            "social_blocks" to 0,
        )

        val appliedCounts = mapOf(
            "achievements" to 0,
            "achievement_facts" to 0,
            "achievement_counters" to 0,
            "audiobooks" to 2,
            "listener_collections" to 0,
            "listener_collection_items" to 0,
            "sources" to 2,
            "chapters" to 2,
            "source_tracks" to 2,
            "bookmarks" to 2,
            "playback_progress" to 2,
            "listening_stats" to 0,
            "playback_sessions" to 0,
            "playback_failures" to 0,
            "playback_events" to 0,
            "tombstones" to 0,
            "corrections" to 0,
            "series" to 0,
            "series_members" to 0,
            "universes" to 0,
            "edition_settings" to 0,
            "works" to 1,
            "editions" to 2,
            "work_sources" to 1,
            "library_entries" to 2,
            "hidden_reviewers" to 0,
            "recommendation_preferences" to 0,
            "work_facets" to 0,
            "works_fts" to 1,
            "work_facet_series" to 0,
            "genre_facets" to 0,
            "work_genres" to 0,
            "genre_assertion_states" to 0,
            "genre_assertions" to 0,
            "edition_facets" to 0,
            "author_facets" to 0,
            "author_aliases" to 0,
            "person_bookmarks" to 0,
            "feed_snapshots" to 0,
            "popularity_assertions" to 0,
            "embedding_vectors" to 0,
            "submission_states" to 0,
            "readthroughs" to 5,
            "friendship_states" to 0,
            "social_blocks" to 0,
        )

        suspend fun seed(dao: AudiobookDao) {
            listOf(workA, workB, workC).forEach { dao.upsertWork(it) }
            dao.insertAudiobooks(listOf(
                AudiobookEntity(
                    id = "pair-book-a", title = "Same work", author = "Shared Author",
                    narrator = "Shared Narrator", description = "Independent A rendition", coverDrawableRes = 0,
                    coverImageUrl = null, genre = "Adventure", sourceUrl = "https://example.test/apply/A",
                    isDownloaded = false, totalDurationSeconds = 120L, totalChapters = 1, rating = 0.25f,
                    sourceTreeUri = null,
                ),
                AudiobookEntity(
                    id = "pair-book-b", title = "Same work", author = "Shared Author",
                    narrator = "Shared Narrator", description = "Independent B rendition", coverDrawableRes = 0,
                    coverImageUrl = null, genre = "Adventure", sourceUrl = "https://example.test/apply/A",
                    isDownloaded = false, totalDurationSeconds = 120L, totalChapters = 1, rating = 0.5f,
                    sourceTreeUri = null,
                ),
            ))
            listOf(entryA, entryB, entryC).forEach {
                dao.upsertLibraryEntry(
                    id = it.id, workId = it.workId, isFavorite = it.isFavorite, createdAt = it.createdAt,
                    downloadProgress = it.downloadProgress, downloadState = it.downloadState,
                )
            }
            listOf(editionA, editionB).forEach { dao.replaceEdition(it) }
            listOf(progressA, progressB).forEach { dao.savePlaybackProgress(it) }
            listOf(bookmarkA, bookmarkB).forEach { dao.insertBookmark(it) }
            dao.insertSources(listOf(sourceA, sourceB))
            dao.insertSourceTrackRows(listOf(trackA, trackB))
            dao.insertChapters(listOf(chapterA, chapterB))
            listOf(claimA, claimB).forEach { dao.upsertWorkSource(it) }
            listOf(ftsA, ftsB).forEach {
                dao.insertWorkSearchRow(it.workId, it.title, it.author, it.series, it.narrator)
            }
            (originalPasses + paperC).forEach { dao.upsertReadthrough(it) }
        }

        fun assertPreview(preview: ManualWorkMergePreview) {
            assertEquals(listOf(A, B), preview.snapshot.rootWorkIds)
            assertEquals(emptyList<ManualWorkMergeConflict>(), preview.conflicts)
            assertEquals(emptyList<ManualWorkMergeConflict>(), preview.snapshot.conflicts)
            assertEquals(listOf(
                ManualWorkMergeParticipant(workA, listOf(ManualWorkMergeEditionPreview(
                    bookId = "pair-book-a", edition = editionA, chapters = listOf(chapterA),
                    progress = progressA, bookmarks = listOf(bookmarkA),
                ))),
                ManualWorkMergeParticipant(workB, listOf(ManualWorkMergeEditionPreview(
                    bookId = "pair-book-b", edition = editionB, chapters = listOf(chapterB),
                    progress = progressB, bookmarks = listOf(bookmarkB),
                ))),
            ), preview.participants)
            assertEquals(listOf(workA, workB), preview.snapshot.library.works.sortedBy { it.id })
            assertEquals(listOf(claimA, claimB), preview.snapshot.catalog.workSources.sortedBy { it.id })
            assertEquals(originalPasses.sortedBy { it.id }, preview.snapshot.library.readthroughs.sortedBy { it.id })
        }

        suspend fun assertStored(dao: AudiobookDao, applied: Boolean) {
            val roots = if (applied) listOf(K) else listOf(A, B)
            val snapshot = dao.readManualWorkMergeRows(roots)
            assertEquals(roots, snapshot.rootWorkIds)
            val actual: Map<String, List<Any>> = mapOf(
                "achievements" to snapshot.retained.achievements,
                "achievement_facts" to snapshot.retained.achievementFacts,
                "achievement_counters" to snapshot.retained.achievementCounters,
                "audiobooks" to snapshot.library.books,
                "listener_collections" to snapshot.personal.collections,
                "listener_collection_items" to snapshot.personal.collectionItems,
                "sources" to snapshot.library.sources,
                "chapters" to snapshot.library.chapters,
                "source_tracks" to snapshot.library.tracks,
                "bookmarks" to snapshot.library.bookmarks,
                "playback_progress" to snapshot.library.progress,
                "listening_stats" to snapshot.retained.stats,
                "playback_sessions" to snapshot.retained.sessions,
                "playback_failures" to snapshot.library.failures,
                "playback_events" to snapshot.library.events,
                "tombstones" to snapshot.personal.tombstones,
                "corrections" to snapshot.personal.corrections,
                "series" to snapshot.catalog.series,
                "series_members" to snapshot.catalog.seriesMembers,
                "universes" to snapshot.catalog.universes,
                "edition_settings" to snapshot.library.settings,
                "works" to snapshot.library.works,
                "editions" to snapshot.library.editions,
                "work_sources" to snapshot.catalog.workSources,
                "library_entries" to snapshot.library.entries,
                "hidden_reviewers" to snapshot.retained.hiddenReviewers,
                "recommendation_preferences" to snapshot.personal.preferences,
                "work_facets" to snapshot.catalog.workFacets,
                "works_fts" to snapshot.catalog.ftsRows,
                "work_facet_series" to snapshot.catalog.facetSeries,
                "genre_facets" to snapshot.catalog.genres,
                "work_genres" to snapshot.catalog.workGenres,
                "genre_assertion_states" to snapshot.catalog.genreStates,
                "genre_assertions" to snapshot.catalog.genreAssertions,
                "edition_facets" to snapshot.catalog.editionFacets,
                "author_facets" to snapshot.catalog.authors,
                "author_aliases" to snapshot.catalog.authorAliases,
                "person_bookmarks" to snapshot.retained.personBookmarks,
                "feed_snapshots" to snapshot.personal.feedSnapshots,
                "popularity_assertions" to snapshot.catalog.popularity,
                "embedding_vectors" to snapshot.catalog.vectors,
                "submission_states" to snapshot.library.submissions,
                "readthroughs" to snapshot.library.readthroughs,
                "friendship_states" to snapshot.retained.friendships,
                "social_blocks" to snapshot.retained.socialBlocks,
            )
            val counts = if (applied) appliedCounts else beforeCounts
            assertEquals(counts.keys, actual.keys)
            assertEquals(counts, actual.mapValues { it.value.size })
            assertEquals(if (applied) 24 else 27, actual.values.sumOf { it.size })
            assertEquals(if (applied) 2 else 3, dao.countWorks())
            val populated: Map<String, List<Any>> = mapOf(
                "works" to (if (applied) listOf(canonicalWork) else listOf(workA, workB)),
                "library_entries" to (if (applied) listOf(entryA.copy(workId = K), entryB.copy(workId = K)) else listOf(entryA, entryB)),
                "audiobooks" to (listOf(bookA, bookB)),
                "editions" to (listOf(editionA, editionB)),
                "playback_progress" to (listOf(progressA, progressB)),
                "bookmarks" to (listOf(bookmarkA, bookmarkB)),
                "chapters" to (listOf(chapterA, chapterB)),
                "sources" to (listOf(sourceA, sourceB)),
                "source_tracks" to (listOf(trackA, trackB)),
                "work_sources" to (if (applied) listOf(canonicalClaim) else listOf(claimA, claimB)),
                "works_fts" to (if (applied) listOf(canonicalFts) else listOf(ftsA, ftsB)),
                "readthroughs" to (if (applied) canonicalPasses else originalPasses),
            )
            for ((table, stored) in actual) {
                val expected = populated[table] ?: emptyList()
                // Complete typed records/multiplicities, not a journal digest or implementation helper.
                assertEquals(table, expected.groupingBy { it }.eachCount(), stored.groupingBy { it }.eachCount())
            }
            val expectedPasses = (if (applied) canonicalPasses else originalPasses) + paperC
            assertEquals(expectedPasses.sortedBy { it.id }, dao.allReadthroughs().sortedBy { it.id })
            for (pass in expectedPasses) assertEquals(pass, dao.readthroughById(pass.id))
            assertEquals(workC, dao.getWorkById("unrelated-work-c"))
            assertEquals(entryC, dao.libraryEntryById("unrelated-entry-c"))
        }
    }

    private companion object {
        const val A = "pair-work-a"
        const val B = "pair-work-b"
        const val K = "same work|shared author"
        const val OPERATION = "00000000-0000-4000-8007-000000000968"
    }
}
