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
class ManualWorkMergeApplyUndoRoomTest {
    @Test(timeout = 180_000)
    fun `approved apply and durable undo preserve both library Editions and exact Entry REAL`() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "manual-work-merge-apply-undo-${UUID.randomUUID()}.db"
        assertFalse(context.getDatabasePath(name).exists())
        fun open(): AudiobookDatabase = Room.databaseBuilder(context, AudiobookDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()
        var database = open()
        try {
            val fixture = Fixture()
            fixture.seed(database.audiobookDao())
            // The sole SQL setup changes a field on an Entry that Apply will rebind.
            database.openHelper.writableDatabase.compileStatement(
                "UPDATE library_entries SET downloadProgress = 0.10000000000000002 WHERE id = 'apply-book-b'",
            ).use { assertEquals(1, it.executeUpdateDelete()) }
            fixture.assertStored(database.audiobookDao(), applied = false)
            assertEntryReal(database)
            fixture.assertPreview(ManualWorkMerge(database.audiobookDao()).prepareManualWorkMerge(A, B))
            assertIdentity(database, state = null)

            val gate = ManualWorkMergeGate(database)
            val commands = ManualWorkMergeCommands(database, gate)
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
            assertTrue("The approved command must apply", applied is ManualWorkMergeApplyResult.Applied)
            val result = applied as ManualWorkMergeApplyResult.Applied
            assertEquals(OPERATION, result.operationId)
            assertEquals(K, result.canonicalWorkId)
            fixture.assertStored(database.audiobookDao(), applied = true)
            assertIdentity(database, WorkMergeOperationState.APPLIED)
            assertEntryReal(database)

            // Durable Undo must work with the database and command module actually reopened.
            database.close()
            database = open()
            fixture.assertStored(database.audiobookDao(), applied = true)
            assertIdentity(database, WorkMergeOperationState.APPLIED)
            val reopenedGate = ManualWorkMergeGate(database)
            val reopenedCommands = ManualWorkMergeCommands(database, reopenedGate)
            val undone = reopenedCommands.undoManualWorkMerge(OPERATION)
            assertTrue("Durable journal must permit Undo after reopening", undone is ManualWorkMergeUndoResult.Undone)
            assertEquals(OPERATION, (undone as ManualWorkMergeUndoResult.Undone).operationId)
            fixture.assertStored(database.audiobookDao(), applied = false)
            assertIdentity(database, WorkMergeOperationState.UNDONE)
            assertEntryReal(database)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    // Only this one raw oracle is reused before Apply, after Apply and after Undo.
    private fun assertEntryReal(database: AudiobookDatabase) {
        database.openHelper.readableDatabase.query(
            "SELECT typeof(downloadProgress), downloadProgress FROM library_entries WHERE id = 'apply-book-b'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("real", cursor.getString(0))
            assertEquals(0x3fb999999999999bL, cursor.getDouble(1).toRawBits())
            assertFalse(cursor.moveToNext())
        }
    }

    private suspend fun assertIdentity(database: AudiobookDatabase, state: WorkMergeOperationState?) {
        val identity = database.workIdentityDao()
        assertEquals(state, identity.operationStateById(OPERATION))
        assertEquals(if (state == null) 0 else 1, identity.operationCount())
        assertEquals(if (state == WorkMergeOperationState.APPLIED) 4 else 0, identity.redirectCount())
        val protection = identity.preservedParticipants(OPERATION).map {
            Triple(it.bookId, it.editionId, it.autoMergeExcluded)
        }
        assertEquals(
            if (state == null) emptyList<Triple<String, String, Boolean>>() else listOf(
                Triple("apply-book-a", "apply-edition-a", true),
                Triple("apply-book-b", "apply-edition-b", true),
            ),
            protection.sortedBy { it.first },
        )
        for ((namespace, oldKey) in listOf(
            "WORK_ID" to "apply-work-a", "WORK_ID" to "apply-work-b",
            "MERGE_KEY" to "legacy a|shared author", "MERGE_KEY" to "legacy b|shared author",
        )) {
            val redirect = identity.redirectByAlias(namespace, oldKey)
            if (state == WorkMergeOperationState.APPLIED) {
                assertTrue("Applied alias must be present: $namespace/$oldKey", redirect != null)
                assertEquals(namespace, redirect!!.namespace)
                assertEquals(oldKey, redirect.oldKey)
                assertEquals(K, redirect.canonicalWorkId)
                assertEquals(OPERATION, redirect.operationId)
            } else {
                assertNull("Alias must be absent before Apply and after Undo", redirect)
            }
        }
    }

    private class Fixture {
        val workA = WorkEntity(
            id = "apply-work-a", mergeKey = "legacy a|shared author", title = "A legacy", author = "Shared Author",
            seriesTitle = null, seriesUrl = null, seriesIndex = null, coverImageUrl = null, addedAt = 10L,
        )
        val workB = WorkEntity(
            id = "apply-work-b", mergeKey = "legacy b|shared author", title = "B legacy", author = "Shared Author",
            seriesTitle = null, seriesUrl = null, seriesIndex = null, coverImageUrl = null, addedAt = 20L,
        )
        val workC = WorkEntity(
            id = "apply-work-c", mergeKey = "unrelated c|other author", title = "Unrelated C", author = "Other Author",
            seriesTitle = null, seriesUrl = null, seriesIndex = null, coverImageUrl = null, addedAt = 30L,
        )
        val entryA = LibraryEntryEntity(
            id = "apply-book-a", workId = "apply-work-a", origin = "UNKNOWN", isFavorite = true, createdAt = 11L,
            downloadProgress = 0.0f, downloadState = "IDLE",
        )
        val entryB = LibraryEntryEntity(
            id = "apply-book-b", workId = "apply-work-b", origin = "UNKNOWN", isFavorite = false, createdAt = 21L,
            downloadProgress = 0.1f, downloadState = "PAUSED",
        )
        val entryC = LibraryEntryEntity(
            id = "apply-book-c", workId = "apply-work-c", origin = "UNKNOWN", isFavorite = true, createdAt = 31L,
            downloadProgress = 0.0f, downloadState = "IDLE",
        )
        val bookA = ManualWorkMergeBookRow(
            id = "apply-book-a", title = "A copy", author = "Shared Author", narrator = "", description = "Before A",
            coverDrawableRes = 0, coverImageUrl = null, genre = "", sourceUrl = "https://example.test/apply/A",
            isDownloaded = false, totalDurationSeconds = 120L, totalChapters = 1, rating = 0.25f, sourceTreeUri = null,
        )
        val bookB = ManualWorkMergeBookRow(
            id = "apply-book-b", title = "B copy", author = "Shared Author", narrator = "", description = "Before B",
            coverDrawableRes = 0, coverImageUrl = null, genre = "", sourceUrl = "https://example.test/apply/B",
            isDownloaded = false, totalDurationSeconds = 180L, totalChapters = 1, rating = 0.5f, sourceTreeUri = null,
        )
        val bookC = ManualWorkMergeBookRow(
            id = "apply-book-c", title = "C copy", author = "Other Author", narrator = "", description = "C preserved",
            coverDrawableRes = 0, coverImageUrl = null, genre = "", sourceUrl = "https://example.test/apply/C",
            isDownloaded = false, totalDurationSeconds = 0L, totalChapters = 0, rating = 0.75f, sourceTreeUri = null,
        )
        val editionA = EditionEntity(
            id = "apply-edition-a", workId = "apply-book-a", language = "uk", narrator = "", totalChapters = 1,
            totalDurationSeconds = 120L, addedAt = 12L,
        )
        val editionB = EditionEntity(
            id = "apply-edition-b", workId = "apply-book-b", language = "en", narrator = "", totalChapters = 1,
            totalDurationSeconds = 180L, addedAt = 22L,
        )
        val progressA = PlaybackProgressEntity(
            editionId = "apply-edition-a", bookId = "apply-book-a", currentChapterIndex = 0,
            currentPositionSeconds = 17L, lastListenedAt = 1000L, isCompleted = false, lastPausedAtEpochMs = 1100L,
            preferredSpeed = 1.25f,
        )
        val progressB = PlaybackProgressEntity(
            editionId = "apply-edition-b", bookId = "apply-book-b", currentChapterIndex = 0,
            currentPositionSeconds = 180L, lastListenedAt = 2000L, isCompleted = true, lastPausedAtEpochMs = null,
            preferredSpeed = 1.5f,
        )
        val bookmarkA = BookmarkEntity(
            id = 101L, bookId = "apply-book-a", editionId = "apply-edition-a", chapterIndex = 0,
            chapterTitle = "Chapter A", timestampSeconds = 9L, note = "A bookmark", createdAt = 1010L,
        )
        val bookmarkB = BookmarkEntity(
            id = 202L, bookId = "apply-book-b", editionId = "apply-edition-b", chapterIndex = 0,
            chapterTitle = "Chapter B", timestampSeconds = 80L, note = "B bookmark", createdAt = 2020L,
        )
        val chapterA = ChapterEntity(
            id = "apply-chapter-a", bookId = "apply-book-a", chapterIndex = 0, title = "Chapter A",
            durationSeconds = 120L, editionId = "apply-edition-a",
        )
        val chapterB = ChapterEntity(
            id = "apply-chapter-b", bookId = "apply-book-b", chapterIndex = 0, title = "Chapter B",
            durationSeconds = 180L, editionId = "apply-edition-b",
        )
        val sourceA = SourceEntity(
            id = "librivox-apply-edition-a", bookId = "apply-book-a", editionId = "apply-edition-a", type = "librivox",
            url = "https://example.test/apply/A", streamOnly = false, addedAt = 12L, lastScanFingerprint = null,
        )
        val sourceB = SourceEntity(
            id = "librivox-apply-edition-b", bookId = "apply-book-b", editionId = "apply-edition-b", type = "librivox",
            url = "https://example.test/apply/B", streamOnly = false, addedAt = 22L, lastScanFingerprint = null,
        )
        val trackA = SourceTrackEntity(
            id = "librivox-apply-edition-a_tr_1", sourceId = "librivox-apply-edition-a", trackIndex = 0,
            url = "https://example.test/apply/A/1.mp3", localFilePath = null, contentHash = null, isDownloaded = false,
        )
        val trackB = SourceTrackEntity(
            id = "librivox-apply-edition-b_tr_1", sourceId = "librivox-apply-edition-b", trackIndex = 0,
            url = "https://example.test/apply/B/1.mp3", localFilePath = null, contentHash = null, isDownloaded = false,
        )
        val claimA = WorkSourceEntity(
            id = "apply-work-a|librivox|b85c98b6", workId = "apply-work-a", sourceId = "librivox",
            sourceUrl = "https://example.test/apply/A", streamOnly = false, coverImageUrl = null,
            durationSeconds = null, addedAt = 12L,
        )
        val claimB = WorkSourceEntity(
            id = "apply-work-b|librivox|b85c98b7", workId = "apply-work-b", sourceId = "librivox",
            sourceUrl = "https://example.test/apply/B", streamOnly = false, coverImageUrl = null,
            durationSeconds = null, addedAt = 22L,
        )
        val ftsA = WorkSearchFtsEntity(
            workId = "apply-work-a", title = "a legacy", author = "shared author", series = "", narrator = "",
        )
        val ftsB = WorkSearchFtsEntity(
            workId = "apply-work-b", title = "b legacy", author = "shared author", series = "", narrator = "",
        )
        val canonicalWork = WorkEntity(
            id = "same work|shared author", mergeKey = "same work|shared author", title = "Same work",
            author = "Shared Author", seriesTitle = null, seriesUrl = null, seriesIndex = null, coverImageUrl = null,
            addedAt = 10L,
        )
        val appliedClaimA = WorkSourceEntity(
            id = "same work|shared author|librivox|b85c98b6", workId = "same work|shared author",
            sourceId = "librivox", sourceUrl = "https://example.test/apply/A", streamOnly = false,
            coverImageUrl = null, durationSeconds = null, addedAt = 12L,
        )
        val appliedClaimB = WorkSourceEntity(
            id = "same work|shared author|librivox|b85c98b7", workId = "same work|shared author",
            sourceId = "librivox", sourceUrl = "https://example.test/apply/B", streamOnly = false,
            coverImageUrl = null, durationSeconds = null, addedAt = 22L,
        )
        val appliedFts = WorkSearchFtsEntity(
            workId = "same work|shared author", title = "same work", author = "shared author", series = "",
            narrator = "",
        )

        val beforeCounts = mapOf(
            "achievements" to 0,
            "achievement_facts" to 0,
            "achievement_counters" to 0,
            "audiobooks" to 3,
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
            "works" to 3,
            "editions" to 2,
            "work_sources" to 2,
            "library_entries" to 3,
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
            "readthroughs" to 0,
            "friendship_states" to 0,
            "social_blocks" to 0,
        )

        val appliedCounts = mapOf(
            "achievements" to 0,
            "achievement_facts" to 0,
            "achievement_counters" to 0,
            "audiobooks" to 3,
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
            "library_entries" to 3,
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
            "readthroughs" to 0,
            "friendship_states" to 0,
            "social_blocks" to 0,
        )

        suspend fun seed(dao: AudiobookDao) {
            listOf(workA, workB, workC).forEach { dao.upsertWork(it) }
            dao.insertAudiobooks(listOf(
                AudiobookEntity(
                    id = "apply-book-a", title = "A copy", author = "Shared Author", narrator = "",
                    description = "Before A", coverDrawableRes = 0, coverImageUrl = null, genre = "",
                    sourceUrl = "https://example.test/apply/A", isDownloaded = false, totalDurationSeconds = 120L,
                    totalChapters = 1, rating = 0.25f, sourceTreeUri = null,
                ),
                AudiobookEntity(
                    id = "apply-book-b", title = "B copy", author = "Shared Author", narrator = "",
                    description = "Before B", coverDrawableRes = 0, coverImageUrl = null, genre = "",
                    sourceUrl = "https://example.test/apply/B", isDownloaded = false, totalDurationSeconds = 180L,
                    totalChapters = 1, rating = 0.5f, sourceTreeUri = null,
                ),
                AudiobookEntity(
                    id = "apply-book-c", title = "C copy", author = "Other Author", narrator = "",
                    description = "C preserved", coverDrawableRes = 0, coverImageUrl = null, genre = "",
                    sourceUrl = "https://example.test/apply/C", isDownloaded = false, totalDurationSeconds = 0L,
                    totalChapters = 0, rating = 0.75f, sourceTreeUri = null,
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
        }

        fun assertPreview(preview: ManualWorkMergePreview) {
            assertEquals(listOf(A, B), preview.snapshot.rootWorkIds)
            assertEquals(emptyList<ManualWorkMergeConflict>(), preview.conflicts)
            assertEquals(emptyList<ManualWorkMergeConflict>(), preview.snapshot.conflicts)
            assertEquals(listOf(
                ManualWorkMergeParticipant(workA, listOf(ManualWorkMergeEditionPreview(
                    bookId = "apply-book-a", edition = editionA, chapters = listOf(chapterA),
                    progress = progressA, bookmarks = listOf(bookmarkA),
                ))),
                ManualWorkMergeParticipant(workB, listOf(ManualWorkMergeEditionPreview(
                    bookId = "apply-book-b", edition = editionB, chapters = listOf(chapterB),
                    progress = progressB, bookmarks = listOf(bookmarkB),
                ))),
            ), preview.participants)
            assertEquals(listOf(workA, workB), preview.snapshot.library.works.sortedBy { it.id })
            assertEquals(
                0x3dcccccd,
                preview.snapshot.library.entries.single { it.id == "apply-book-b" }.downloadProgress.toRawBits(),
            )
        }

        suspend fun assertStored(dao: AudiobookDao, applied: Boolean) {
            val roots = if (applied) listOf(C, K) else listOf(A, B, C)
            val snapshot = dao.readManualWorkMergeRows(roots)
            assertEquals(roots.sorted(), snapshot.rootWorkIds)
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
            assertEquals(if (applied) 23 else 25, actual.values.sumOf { it.size })
            assertEquals(if (applied) 2 else 3, dao.countWorks())
            val populated: Map<String, List<Any>> = mapOf(
                "works" to (if (applied) listOf(canonicalWork, workC) else listOf(workA, workB, workC)),
                "library_entries" to (if (applied) listOf(
                    entryA.copy(workId = K), entryB.copy(workId = K), entryC,
                ) else listOf(entryA, entryB, entryC)),
                "audiobooks" to (listOf(bookA, bookB, bookC)),
                "editions" to (listOf(editionA, editionB)),
                "playback_progress" to (listOf(progressA, progressB)),
                "bookmarks" to (listOf(bookmarkA, bookmarkB)),
                "chapters" to (listOf(chapterA, chapterB)),
                "sources" to (listOf(sourceA, sourceB)),
                "source_tracks" to (listOf(trackA, trackB)),
                "work_sources" to (if (applied) listOf(appliedClaimA, appliedClaimB) else listOf(claimA, claimB)),
                "works_fts" to (if (applied) listOf(appliedFts) else listOf(ftsA, ftsB)),
            )
            for ((table, rows) in actual) {
                val expected = populated[table] ?: emptyList()
                // Full typed records and multiplicities; no digest or journal payload is an oracle.
                assertEquals(table, expected.groupingBy { it }.eachCount(), rows.groupingBy { it }.eachCount())
            }
            assertEquals(
                0x3dcccccd,
                snapshot.library.entries.single { it.id == "apply-book-b" }.downloadProgress.toRawBits(),
            )
        }
    }

    private companion object {
        const val A = "apply-work-a"
        const val B = "apply-work-b"
        const val C = "apply-work-c"
        const val K = "same work|shared author"
        const val OPERATION = "00000000-0000-4000-8000-000000000968"
    }
}
