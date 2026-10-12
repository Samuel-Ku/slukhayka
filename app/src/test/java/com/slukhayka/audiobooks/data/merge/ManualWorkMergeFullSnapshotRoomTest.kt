package com.slukhayka.audiobooks.data.merge

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AchievementCounterEntity
import com.slukhayka.audiobooks.data.db.AchievementEntity
import com.slukhayka.audiobooks.data.db.AchievementFactEntity
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.AuthorAliasEntity
import com.slukhayka.audiobooks.data.db.AuthorFacetEntity
import com.slukhayka.audiobooks.data.db.BlockEntity
import com.slukhayka.audiobooks.data.db.BookmarkEntity
import com.slukhayka.audiobooks.data.db.ChapterEntity
import com.slukhayka.audiobooks.data.db.CorrectionEntity
import com.slukhayka.audiobooks.data.db.EditionEntity
import com.slukhayka.audiobooks.data.db.EditionFacetEntity
import com.slukhayka.audiobooks.data.db.EditionSettingsEntity
import com.slukhayka.audiobooks.data.db.EmbeddingVectorEntity
import com.slukhayka.audiobooks.data.db.FeedSnapshotEntity
import com.slukhayka.audiobooks.data.db.FriendshipStateEntity
import com.slukhayka.audiobooks.data.db.GenreAssertionEntity
import com.slukhayka.audiobooks.data.db.GenreAssertionStateEntity
import com.slukhayka.audiobooks.data.db.GenreFacetEntity
import com.slukhayka.audiobooks.data.db.HiddenReviewerEntity
import com.slukhayka.audiobooks.data.db.LibraryEntryEntity
import com.slukhayka.audiobooks.data.db.ListenerCollectionEntity
import com.slukhayka.audiobooks.data.db.ListenerCollectionItemEntity
import com.slukhayka.audiobooks.data.db.ListeningStatEntity
import com.slukhayka.audiobooks.data.db.PersonBookmarkEntity
import com.slukhayka.audiobooks.data.db.PlaybackEventEntity
import com.slukhayka.audiobooks.data.db.PlaybackFailureEntity
import com.slukhayka.audiobooks.data.db.PlaybackProgressEntity
import com.slukhayka.audiobooks.data.db.PlaybackSessionEntity
import com.slukhayka.audiobooks.data.db.PopularityAssertionEntity
import com.slukhayka.audiobooks.data.db.ReadthroughEntity
import com.slukhayka.audiobooks.data.db.RecommendationPreferenceEntity
import com.slukhayka.audiobooks.data.db.SeriesEntity
import com.slukhayka.audiobooks.data.db.SeriesMemberEntity
import com.slukhayka.audiobooks.data.db.SourceEntity
import com.slukhayka.audiobooks.data.db.SourceTrackEntity
import com.slukhayka.audiobooks.data.db.SubmissionStateEntity
import com.slukhayka.audiobooks.data.db.TombstoneEntity
import com.slukhayka.audiobooks.data.db.UniverseEntity
import com.slukhayka.audiobooks.data.db.WorkEntity
import com.slukhayka.audiobooks.data.db.WorkFacetEntity
import com.slukhayka.audiobooks.data.db.WorkFacetSeriesEntity
import com.slukhayka.audiobooks.data.db.WorkGenreEntity
import com.slukhayka.audiobooks.data.db.WorkSearchFtsEntity
import com.slukhayka.audiobooks.data.db.WorkSourceEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ManualWorkMergeFullSnapshotRoomTest {
    @Test(timeout = 90_000)
    fun `prepare retains all populated carriers and fingerprints only coherent pair dependencies`() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val db = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = db.audiobookDao()
            val collections = db.listenerCollectionsDao()
            val achievements = db.achievementDao()
            val social = db.socialDao()
            val workA = WorkEntity(
                id = "work-a",
                mergeKey = "work-a",
                title = "Тінь; A\nрядок",
                author = "Автор",
                seriesTitle = "Серія",
                seriesUrl = "https://example.invalid/series",
                seriesIndex = 1,
                coverImageUrl = "https://example.invalid/cover-a",
                addedAt = 101L,
            )
            val workB = WorkEntity(
                id = "work-b",
                mergeKey = "work-b",
                title = "Тінь; A\nрядок",
                author = "Автор",
                seriesTitle = "Серія",
                seriesUrl = "https://example.invalid/series",
                seriesIndex = 2,
                coverImageUrl = null,
                addedAt = 102L,
            )
            val workC = WorkEntity(
                id = "work-c",
                mergeKey = "work-c",
                title = "Інше",
                author = "Інший автор",
                seriesTitle = null,
                seriesUrl = null,
                seriesIndex = null,
                coverImageUrl = null,
                addedAt = 103L,
            )
            val entryA = LibraryEntryEntity(
                id = "book-a",
                workId = "work-a",
                origin = "EXPLICIT_IMPORT",
                isFavorite = false,
                createdAt = 201L,
                downloadProgress = 0.0f,
                downloadState = "IDLE",
            )
            val entryB = LibraryEntryEntity(
                id = "book-b",
                workId = "work-b",
                origin = "EXPLICIT_IMPORT",
                isFavorite = false,
                createdAt = 202L,
                downloadProgress = 0.0f,
                downloadState = "IDLE",
            )
            val entryPaper = LibraryEntryEntity(
                id = "paper-a",
                workId = "work-a",
                origin = "EXPLICIT_SAVE",
                isFavorite = false,
                createdAt = 203L,
                downloadProgress = 0.0f,
                downloadState = "IDLE",
            )
            val entryC = LibraryEntryEntity(
                id = "book-c",
                workId = "work-c",
                origin = "EXPLICIT_IMPORT",
                isFavorite = false,
                createdAt = 204L,
                downloadProgress = 0.0f,
                downloadState = "IDLE",
            )
            val bookA = AudiobookEntity(
                id = "book-a",
                title = "Тінь; A\nрядок",
                author = "Автор",
                narrator = "Олена",
                description = "опис; A\nсирий",
                coverDrawableRes = 0,
                coverImageUrl = null,
                genre = "Фентезі",
                sourceUrl = "",
                isDownloaded = true,
                totalDurationSeconds = 1800L,
                totalChapters = 1,
                rating = 4.5f,
                sourceTreeUri = "content://fixture/tree-a",
            )
            val bookB = AudiobookEntity(
                id = "book-b",
                title = "Тінь; A\nрядок",
                author = "Автор",
                narrator = "David",
                description = "B",
                coverDrawableRes = 0,
                coverImageUrl = null,
                genre = "Fantasy",
                sourceUrl = "",
                isDownloaded = false,
                totalDurationSeconds = 3600L,
                totalChapters = 1,
                rating = 3.25f,
                sourceTreeUri = null,
            )
            val bookC = AudiobookEntity(
                id = "book-c",
                title = "Інше",
                author = "Інший автор",
                narrator = "Інший",
                description = "C",
                coverDrawableRes = 0,
                coverImageUrl = null,
                genre = "Інше",
                sourceUrl = "",
                isDownloaded = false,
                totalDurationSeconds = 60L,
                totalChapters = 1,
                rating = 2.0f,
                sourceTreeUri = null,
            )
            val editionA = EditionEntity(
                id = "edition-a",
                workId = "book-a",
                language = "uk",
                narrator = "Олена",
                totalChapters = 1,
                totalDurationSeconds = 1800L,
                addedAt = 301L,
            )
            val editionCatalog = EditionEntity(
                id = "edition-catalog-a",
                workId = "work-a",
                language = "uk",
                narrator = "Олена",
                totalChapters = 0,
                totalDurationSeconds = 2400L,
                addedAt = 302L,
            )
            val editionB = EditionEntity(
                id = "edition-b",
                workId = "book-b",
                language = "en",
                narrator = "David",
                totalChapters = 1,
                totalDurationSeconds = 3600L,
                addedAt = 303L,
            )
            val editionC = EditionEntity(
                id = "edition-c",
                workId = "book-c",
                language = "uk",
                narrator = "Інший",
                totalChapters = 1,
                totalDurationSeconds = 60L,
                addedAt = 304L,
            )
            val sourceA = SourceEntity(
                id = "local-a",
                bookId = "book-a",
                editionId = "edition-a",
                type = "local",
                url = "content://fixture/a",
                streamOnly = false,
                addedAt = 401L,
                lastScanFingerprint = "scan-a",
            )
            val sourceB = SourceEntity(
                id = "local-b",
                bookId = "book-b",
                editionId = "edition-b",
                type = "local",
                url = "content://fixture/b",
                streamOnly = false,
                addedAt = 402L,
                lastScanFingerprint = null,
            )
            val sourceC = SourceEntity(
                id = "local-c",
                bookId = "book-c",
                editionId = "edition-c",
                type = "local",
                url = "content://fixture/c",
                streamOnly = false,
                addedAt = 403L,
                lastScanFingerprint = null,
            )
            val trackA = SourceTrackEntity(
                id = "track-a",
                sourceId = "local-a",
                trackIndex = 0,
                url = "content://fixture/a/one",
                localFilePath = "/fixture/not-opened/a.mp3",
                contentHash = "0000000000000000000000000000000000000000000000000000000000000000",
                isDownloaded = true,
            )
            val trackB = SourceTrackEntity(
                id = "track-b",
                sourceId = "local-b",
                trackIndex = 0,
                url = "content://fixture/b/one",
                localFilePath = null,
                contentHash = null,
                isDownloaded = false,
            )
            val trackC = SourceTrackEntity(
                id = "track-c",
                sourceId = "local-c",
                trackIndex = 0,
                url = "content://fixture/c/one",
                localFilePath = null,
                contentHash = null,
                isDownloaded = false,
            )
            val chapterA = ChapterEntity(
                id = "chapter-a",
                bookId = "book-a",
                chapterIndex = 0,
                title = "Розділ A",
                durationSeconds = 1800L,
                editionId = "edition-a",
            )
            val chapterB = ChapterEntity(
                id = "chapter-b",
                bookId = "book-b",
                chapterIndex = 0,
                title = "Chapter B",
                durationSeconds = 3600L,
                editionId = "edition-b",
            )
            val chapterC = ChapterEntity(
                id = "chapter-c",
                bookId = "book-c",
                chapterIndex = 0,
                title = "C",
                durationSeconds = 60L,
                editionId = "edition-c",
            )
            val progressA = PlaybackProgressEntity(
                editionId = "edition-a",
                bookId = "book-a",
                currentChapterIndex = 0,
                currentPositionSeconds = 137L,
                lastListenedAt = 501L,
                isCompleted = false,
                lastPausedAtEpochMs = 500001L,
                preferredSpeed = 1.25f,
            )
            val progressB = PlaybackProgressEntity(
                editionId = "edition-b",
                bookId = "book-b",
                currentChapterIndex = 0,
                currentPositionSeconds = 911L,
                lastListenedAt = 502L,
                isCompleted = false,
                lastPausedAtEpochMs = null,
                preferredSpeed = 1.75f,
            )
            val progressC = PlaybackProgressEntity(
                editionId = "edition-c",
                bookId = "book-c",
                currentChapterIndex = 0,
                currentPositionSeconds = 3L,
                lastListenedAt = 503L,
                isCompleted = false,
                lastPausedAtEpochMs = null,
                preferredSpeed = null,
            )
            val bookmarkA = BookmarkEntity(
                id = 701L,
                bookId = "book-a",
                editionId = "edition-a",
                chapterIndex = 0,
                chapterTitle = "Розділ A",
                timestampSeconds = 83L,
                note = "A;\nзакладка",
                createdAt = 601L,
            )
            val bookmarkB = BookmarkEntity(
                id = 702L,
                bookId = "book-b",
                editionId = "edition-b",
                chapterIndex = 0,
                chapterTitle = "Chapter B",
                timestampSeconds = 900L,
                note = "B",
                createdAt = 602L,
            )
            val bookmarkC = BookmarkEntity(
                id = 703L,
                bookId = "book-c",
                editionId = "edition-c",
                chapterIndex = 0,
                chapterTitle = "C",
                timestampSeconds = 2L,
                note = "C",
                createdAt = 603L,
            )
            val settingsA = EditionSettingsEntity(
                bookId = "book-a",
                sourceKey = "",
                rewindSeconds = 17,
                sleepTimerDefaultSeconds = 600,
                volumeBoostEnabled = false,
                silenceSkipEnabled = false,
                updatedAt = 10101L,
            )
            val settingsB = EditionSettingsEntity(
                bookId = "book-b",
                sourceKey = "secondary|давнє",
                rewindSeconds = null,
                sleepTimerDefaultSeconds = null,
                volumeBoostEnabled = false,
                silenceSkipEnabled = false,
                updatedAt = 10102L,
            )
            val settingsC = EditionSettingsEntity(
                bookId = "book-c",
                sourceKey = "",
                rewindSeconds = 9,
                sleepTimerDefaultSeconds = 300,
                volumeBoostEnabled = false,
                silenceSkipEnabled = false,
                updatedAt = 10103L,
            )
            val audioPassA = ReadthroughEntity(
                id = "pass-a",
                libraryEntryId = "book-a",
                workId = "work-a",
                format = "AUDIO",
                state = "IN_PROGRESS",
                startedAt = 801L,
                finishedAt = null,
                editionId = "edition-a",
                unit = "SECONDS",
                unitValue = 137,
                journalJson = "[{\"at\":801,\"u\":\"SECONDS\",\"v\":137}]",
            )
            val paperPassA = ReadthroughEntity(
                id = "pass-paper-a",
                libraryEntryId = "paper-a",
                workId = "work-a",
                format = "PAPER",
                state = "IN_PROGRESS",
                startedAt = 802L,
                finishedAt = null,
                editionId = null,
                unit = "PAGES",
                unitValue = 17,
                journalJson = "[ {\"at\":802,\"u\":\"PAGES\",\"v\":17} ]",
            )
            val finishedPassB = ReadthroughEntity(
                id = "pass-b-1",
                libraryEntryId = "book-b",
                workId = "work-b",
                format = "AUDIO",
                state = "FINISHED",
                startedAt = 803L,
                finishedAt = 804L,
                editionId = "edition-b",
                unit = "SECONDS",
                unitValue = 3600,
                journalJson = "[]",
            )
            val repeatedPassB = ReadthroughEntity(
                id = "pass-b-2",
                libraryEntryId = "book-b",
                workId = "work-b",
                format = "AUDIO",
                state = "IN_PROGRESS",
                startedAt = 805L,
                finishedAt = null,
                editionId = "edition-b",
                unit = "SECONDS",
                unitValue = 911,
                journalJson = "[]",
            )
            val eventA = PlaybackEventEntity(
                id = 901L,
                bookId = "book-a",
                sourceKey = "",
                kind = "SEEK",
                chapterIndex = 0,
                positionSeconds = 137L,
                fromPositionSeconds = 20L,
                timestamp = 806L,
                deviceId = "fixture-device",
            )
            val eventB = PlaybackEventEntity(
                id = 902L,
                bookId = "book-b",
                sourceKey = "historical|source",
                kind = "PAUSE",
                chapterIndex = 0,
                positionSeconds = 911L,
                fromPositionSeconds = null,
                timestamp = 807L,
                deviceId = "",
            )
            val failureA = PlaybackFailureEntity(
                id = 903L,
                timestamp = 808L,
                bookId = "book-a",
                chapterIndex = 0,
                errorCodeName = "ERROR_CODE_IO_FILE_NOT_FOUND",
                streamUrl = "content://fixture/a/one",
                audioEngineMode = "IDLE",
                category = "FILE_UNAVAILABLE",
            )
            val submissionA = SubmissionStateEntity(
                sourceId = "local-a",
                url = "content://fixture/a",
                bookId = "book-a",
                metadataJson = " {\"title\":\"A;\",\"n\":null} ",
                channelId = "fixture-channel",
                state = "AWAITING_PLAY",
                reason = null,
                createdAt = 809L,
                updatedAt = 810L,
            )
            val claimA = WorkSourceEntity(
                id = "claim-a",
                workId = "work-a",
                sourceId = "provider-a",
                sourceUrl = "https://example.invalid/a",
                streamOnly = true,
                coverImageUrl = null,
                durationSeconds = null,
                addedAt = 1001L,
            )
            val claimB = WorkSourceEntity(
                id = "claim-b",
                workId = "work-b",
                sourceId = "provider-b",
                sourceUrl = "https://example.invalid/b",
                streamOnly = false,
                coverImageUrl = null,
                durationSeconds = 3600L,
                addedAt = 1002L,
            )
            val claimC = WorkSourceEntity(
                id = "claim-c",
                workId = "work-c",
                sourceId = "provider-c",
                sourceUrl = "https://example.invalid/c",
                streamOnly = false,
                coverImageUrl = null,
                durationSeconds = null,
                addedAt = 1003L,
            )
            val seriesMain = SeriesEntity(
                id = "series-main",
                title = "Серія",
                url = "https://example.invalid/series",
                universeId = "universe-main",
                positionInUniverse = 1,
                publicationYear = 2020,
            )
            val memberA = SeriesMemberEntity(
                workId = "work-a",
                seriesId = "series-main",
                position = 1,
                resolvedAt = 1101L,
            )
            val memberB = SeriesMemberEntity(
                workId = "work-b",
                seriesId = "series-main",
                position = 2,
                resolvedAt = 1102L,
            )
            val universeMain = UniverseEntity(
                id = "universe-main",
                name = "Всесвіт",
            )
            val facetA = WorkFacetEntity(
                workId = "work-a",
                canonicalAuthorId = "author-a",
                updatedAt = 1201L,
            )
            val facetB = WorkFacetEntity(
                workId = "work-b",
                canonicalAuthorId = "author-b",
                updatedAt = 1202L,
            )
            val facetSeriesA = WorkFacetSeriesEntity(
                workId = "work-a",
                seriesId = "series-main",
            )
            val facetSeriesB = WorkFacetSeriesEntity(
                workId = "work-b",
                seriesId = "series-main",
            )
            val genreFantasy = GenreFacetEntity(
                id = "fantasy",
                displayName = "Фентезі",
                normalizedName = "фентезі",
            )
            val stateA = GenreAssertionStateEntity(
                workId = "work-a",
                sourceId = "provider-a",
                documentUpdatedAt = 1301L,
                provenance = "enumeration",
            )
            val emptyStateA = GenreAssertionStateEntity(
                workId = "work-a",
                sourceId = "provider-empty",
                documentUpdatedAt = 1302L,
                provenance = "enumeration",
            )
            val stateB = GenreAssertionStateEntity(
                workId = "work-b",
                sourceId = "provider-b",
                documentUpdatedAt = 1303L,
                provenance = "search",
            )
            val genreClaimA = GenreAssertionEntity(
                id = "genre-claim-a",
                assertionId = "raw-a",
                workId = "work-a",
                genreId = "fantasy",
                rawText = "Фентезі; raw",
                sourceId = "provider-a",
                observedAt = 1301L,
            )
            val genreClaimB = GenreAssertionEntity(
                id = "genre-claim-b",
                assertionId = "raw-b",
                workId = "work-b",
                genreId = "fantasy",
                rawText = "Fantasy",
                sourceId = "provider-b",
                observedAt = 1303L,
            )
            val workGenreA = WorkGenreEntity(
                workId = "work-a",
                genreId = "fantasy",
                sourceId = "provider-a",
            )
            val workGenreB = WorkGenreEntity(
                workId = "work-b",
                genreId = "fantasy",
                sourceId = "provider-b",
            )
            val editionFacetA = EditionFacetEntity(
                editionId = "edition-a",
                workId = "work-a",
                narratorId = "narrator-a",
                language = "uk",
                durationSeconds = 1800L,
                durationBucketId = null,
                chapterCount = 1,
                isAbridged = false,
                availabilityAvailable = true,
                availabilityObservedAtMillis = 1401L,
                availabilityTtlSeconds = 60L,
                updatedAt = 1401L,
            )
            val editionFacetCatalog = EditionFacetEntity(
                editionId = "edition-catalog-a",
                workId = "work-a",
                narratorId = null,
                language = "uk",
                durationSeconds = 2400L,
                durationBucketId = null,
                chapterCount = 0,
                isAbridged = null,
                availabilityAvailable = null,
                availabilityObservedAtMillis = null,
                availabilityTtlSeconds = null,
                updatedAt = 1402L,
            )
            val editionFacetB = EditionFacetEntity(
                editionId = "edition-b",
                workId = "work-b",
                narratorId = "narrator-b",
                language = "en",
                durationSeconds = 3600L,
                durationBucketId = null,
                chapterCount = 1,
                isAbridged = null,
                availabilityAvailable = null,
                availabilityObservedAtMillis = null,
                availabilityTtlSeconds = null,
                updatedAt = 1403L,
            )
            val authorA = AuthorFacetEntity(
                id = "author-a",
                displayName = "Автор",
                normalizedName = "автор",
                updatedAt = 1501L,
            )
            val authorB = AuthorFacetEntity(
                id = "author-b",
                displayName = "Автор",
                normalizedName = "автор",
                updatedAt = 1502L,
            )
            val aliasA = AuthorAliasEntity(
                authorId = "author-a",
                normalizedAlias = "автор",
                rawAlias = "Автор &amp;",
                sourceId = "provider-a",
                observedAt = 1501L,
            )
            val aliasB = AuthorAliasEntity(
                authorId = "author-b",
                normalizedAlias = "автор",
                rawAlias = "Автор",
                sourceId = "provider-b",
                observedAt = 1502L,
            )
            val ftsA = WorkSearchFtsEntity(
                workId = "work-a",
                title = "тінь",
                author = "автор",
                series = "серія",
                narrator = "олена",
            )
            val ftsADuplicate = WorkSearchFtsEntity(
                workId = "work-a",
                title = "тінь",
                author = "автор",
                series = "серія",
                narrator = "олена",
            )
            val ftsB = WorkSearchFtsEntity(
                workId = "work-b",
                title = "тінь",
                author = "автор",
                series = "серія",
                narrator = "david",
            )
            val ftsC = WorkSearchFtsEntity(
                workId = "work-c",
                title = "інше",
                author = "інший",
                series = "",
                narrator = "інший",
            )
            val popularityA = PopularityAssertionEntity(
                id = "rank-a",
                kind = "rank",
                mergeKey = "work-a",
                rawValue = "7",
                sourceId = "provider-a",
                observedAt = 1601L,
            )
            val vectorA = EmbeddingVectorEntity(
                workId = "work-a",
                textHash = "1111111111111111111111111111111111111111111111111111111111111111",
                vector = byteArrayOf(0, 1, -1, 127),
                updatedAt = 1602L,
            )
            val vectorC = EmbeddingVectorEntity(
                workId = "work-c",
                textHash = "2222222222222222222222222222222222222222222222222222222222222222",
                vector = byteArrayOf(42, 0, 0, 0),
                updatedAt = 1603L,
            )
            val neverMatch = CorrectionEntity(
                mergeKey = "work-b",
                kind = "NEVER_MATCH",
                value = "work-a",
                origin = "USER_MADE",
                updatedAt = 1701L,
                updatedBy = "fixture-device",
            )
            val chapterOrder = CorrectionEntity(
                mergeKey = "chapter-order:book-a",
                kind = "FIELD",
                value = "Y2hhcHRlci1h",
                origin = "USER_MADE",
                updatedAt = 1702L,
                updatedBy = "fixture-device",
            )
            val tombstoneA = TombstoneEntity(
                bookId = "book-a",
                deletedAt = 1703L,
            )
            val preferenceA = RecommendationPreferenceEntity(
                kind = "HIDE_WORK",
                targetKey = "work-a",
                sourceWorkId = "work-b",
                createdAt = 1704L,
            )
            val collectionMain = ListenerCollectionEntity(
                id = "collection-main",
                title = "Моя колекція",
                description = "Причини;\nокремі",
                createdAt = 1801L,
                sourceDocumentId = "source-document",
                sourceTitle = "Оригінал",
                sourcePseudonym = "fixture-friend",
                snapshotAt = 1800L,
            )
            val itemA = ListenerCollectionItemEntity(
                collectionId = "collection-main",
                bookId = "book-a",
                reason = "A reason",
                addedAt = 1802L,
            )
            val itemB = ListenerCollectionItemEntity(
                collectionId = "collection-main",
                bookId = "book-b",
                reason = "B reason",
                addedAt = 1803L,
            )
            val feedA = FeedSnapshotEntity(
                sourceId = "provider-a",
                feedKey = "catalog",
                pageCursor = "p1",
                fetchedAt = 1804L,
                cardsJson = " [{\"id\":\"work-a\",\"raw\":\";\"}] ",
            )
            val feedC = FeedSnapshotEntity(
                sourceId = "provider-c",
                feedKey = "catalog",
                pageCursor = "",
                fetchedAt = 1805L,
                cardsJson = "[{\"id\":\"work-c\"}]",
            )
            val globalStats = ListeningStatEntity(
                dateIso = "2026-10-01",
                listenedSeconds = 2L,
                verifiedListenedMillis = 2100L,
                offlineListenedMillis = 1000L,
                castListenedMillis = 500L,
                nightListenedMillis = 200L,
            )
            val globalSession = PlaybackSessionEntity(
                id = 2001L,
                startedAt = 2000L,
                endedAt = 2100L,
                verifiedMillis = 100L,
                offlineMillis = 50L,
                castMillis = 25L,
            )
            val globalAward = AchievementEntity(
                id = "fixture-award",
                earnedAt = 2002L,
                seenAt = null,
                pinnedAt = 2003L,
            )
            val globalFact = AchievementFactEntity(
                key = "opaque-global-fact",
                observedAt = 2004L,
            )
            val globalCounter = AchievementCounterEntity(
                key = "global-counter",
                count = 5L,
            )
            val globalPerson = PersonBookmarkEntity(
                kind = "AUTHOR",
                id = "author-a",
                displayName = "Автор",
                normalizedName = "автор",
                createdAt = 2005L,
                lastSeenAt = 2006L,
                lastNotifiedAt = 2007L,
                lastNotifiedCount = 2,
                notifyEnabled = false,
                updatedAt = 2008L,
            )
            val globalMute = HiddenReviewerEntity(
                authorName = "Автор відгуку",
                hiddenAt = 2009L,
            )
            val globalFriend = FriendshipStateEntity(
                pseudonym = "fixture-friend",
                state = "ACCEPTED",
                updatedAt = 2010L,
            )
            val globalBlock = BlockEntity(
                pseudonym = "fixture-other",
                direction = "BY_ME",
                blockedAt = 2011L,
            )
            val globalCounterAfter = globalCounter.copy(count = 6L)
            val bookmarkAfter = bookmarkA.copy(note = "A changed;\nзакладка")

            dao.upsertWork(workA)
            dao.upsertWork(workB)
            dao.upsertWork(workC)
            dao.insertAudiobooks(listOf(bookA, bookB, bookC))
            dao.insertEdition(editionA)
            dao.insertEdition(editionCatalog)
            dao.insertEdition(editionB)
            dao.insertEdition(editionC)
            dao.insertSources(listOf(sourceA, sourceB, sourceC))
            dao.insertSourceTrackRows(listOf(trackA, trackB, trackC))
            dao.insertChapters(listOf(chapterA, chapterB, chapterC))
            dao.savePlaybackProgress(progressA)
            dao.savePlaybackProgress(progressB)
            dao.savePlaybackProgress(progressC)
            dao.insertBookmark(bookmarkA)
            dao.insertBookmark(bookmarkB)
            dao.insertBookmark(bookmarkC)
            dao.upsertReadthrough(audioPassA)
            dao.upsertReadthrough(paperPassA)
            dao.upsertReadthrough(finishedPassB)
            dao.upsertReadthrough(repeatedPassB)
            dao.insertPlaybackEvent(eventA)
            dao.insertPlaybackEvent(eventB)
            dao.insertPlaybackFailure(failureA)
            dao.upsertSubmissionState(submissionA)
            dao.upsertWorkSource(claimA)
            dao.upsertWorkSource(claimB)
            dao.upsertWorkSource(claimC)
            dao.upsertSeries(seriesMain)
            dao.upsertSeriesMember(memberA)
            dao.upsertSeriesMember(memberB)
            dao.upsertUniverse(universeMain)
            dao.insertWorkFacetSeries(listOf(facetSeriesA, facetSeriesB))
            dao.insertGenreFacets(listOf(genreFantasy))
            dao.upsertGenreAssertionState(stateA)
            dao.upsertGenreAssertionState(emptyStateA)
            dao.upsertGenreAssertionState(stateB)
            dao.insertGenreAssertions(listOf(genreClaimA, genreClaimB))
            dao.insertWorkGenres(listOf(workGenreA, workGenreB))
            dao.insertAuthorAliases(listOf(aliasA, aliasB))
            dao.upsertPopularityAssertions(listOf(popularityA))
            dao.upsertEmbeddingVectors(listOf(vectorA, vectorC))
            dao.upsertCorrection(neverMatch)
            dao.upsertCorrection(chapterOrder)
            dao.insertTombstone(tombstoneA)
            dao.upsertRecommendationPreference(preferenceA)
            dao.upsertFeedSnapshot(feedA)
            dao.upsertFeedSnapshot(feedC)
            dao.saveListeningStat(globalStats)
            dao.insertPlaybackSession(globalSession)
            dao.upsertPersonBookmark(globalPerson)
            dao.hideAuthor(globalMute)
            dao.insertLibraryEntryWithOrigin(entryA.id, entryA.workId, entryA.origin, entryA.createdAt)
            dao.insertLibraryEntryWithOrigin(entryB.id, entryB.workId, entryB.origin, entryB.createdAt)
            dao.insertLibraryEntryWithOrigin(entryPaper.id, entryPaper.workId, entryPaper.origin, entryPaper.createdAt)
            dao.insertLibraryEntryWithOrigin(entryC.id, entryC.workId, entryC.origin, entryC.createdAt)
            dao.mergeWorkFacet(facetA.workId, facetA.canonicalAuthorId, facetA.updatedAt)
            dao.mergeWorkFacet(facetB.workId, facetB.canonicalAuthorId, facetB.updatedAt)
            dao.mergeAuthorFacet(authorA.id, authorA.displayName, authorA.normalizedName, authorA.updatedAt)
            dao.mergeAuthorFacet(authorB.id, authorB.displayName, authorB.normalizedName, authorB.updatedAt)
            dao.mergeEditionFacet(editionFacetA.editionId, editionFacetA.workId, editionFacetA.narratorId, editionFacetA.language, editionFacetA.durationSeconds, editionFacetA.durationBucketId, editionFacetA.chapterCount, editionFacetA.isAbridged, editionFacetA.availabilityAvailable, editionFacetA.availabilityObservedAtMillis, editionFacetA.availabilityTtlSeconds, editionFacetA.updatedAt)
            dao.mergeEditionFacet(editionFacetCatalog.editionId, editionFacetCatalog.workId, editionFacetCatalog.narratorId, editionFacetCatalog.language, editionFacetCatalog.durationSeconds, editionFacetCatalog.durationBucketId, editionFacetCatalog.chapterCount, editionFacetCatalog.isAbridged, editionFacetCatalog.availabilityAvailable, editionFacetCatalog.availabilityObservedAtMillis, editionFacetCatalog.availabilityTtlSeconds, editionFacetCatalog.updatedAt)
            dao.mergeEditionFacet(editionFacetB.editionId, editionFacetB.workId, editionFacetB.narratorId, editionFacetB.language, editionFacetB.durationSeconds, editionFacetB.durationBucketId, editionFacetB.chapterCount, editionFacetB.isAbridged, editionFacetB.availabilityAvailable, editionFacetB.availabilityObservedAtMillis, editionFacetB.availabilityTtlSeconds, editionFacetB.updatedAt)
            dao.insertWorkSearchRow(ftsA.workId, ftsA.title, ftsA.author, ftsA.series, ftsA.narrator)
            dao.insertWorkSearchRow(ftsADuplicate.workId, ftsADuplicate.title, ftsADuplicate.author, ftsADuplicate.series, ftsADuplicate.narrator)
            dao.insertWorkSearchRow(ftsB.workId, ftsB.title, ftsB.author, ftsB.series, ftsB.narrator)
            dao.insertWorkSearchRow(ftsC.workId, ftsC.title, ftsC.author, ftsC.series, ftsC.narrator)
            collections.insertCollection(collectionMain)
            collections.insertItem(itemA)
            collections.insertItem(itemB)
            achievements.insertAwards(listOf(globalAward))
            achievements.insertFact(globalFact)
            achievements.insertCounter(globalCounter)
            social.upsertFriendshipState(globalFriend)
            social.insertBlock(globalBlock)
            val legacy = db.openHelper.writableDatabase
            check(legacy.insert("edition_settings", SQLiteDatabase.CONFLICT_ABORT, ContentValues().apply {
                put("bookId", "book-a")
                put("sourceKey", "")
                put("rewindSeconds", 17)
                put("sleepTimerDefaultSeconds", 600)
                put("volumeBoostEnabled", 0)
                put("silenceSkipEnabled", 0)
                put("updatedAt", 10101L)
            }) != -1L)
            check(legacy.insert("edition_settings", SQLiteDatabase.CONFLICT_ABORT, ContentValues().apply {
                put("bookId", "book-b")
                put("sourceKey", "secondary|давнє")
                putNull("rewindSeconds")
                putNull("sleepTimerDefaultSeconds")
                put("volumeBoostEnabled", 0)
                put("silenceSkipEnabled", 0)
                put("updatedAt", 10102L)
            }) != -1L)
            check(legacy.insert("edition_settings", SQLiteDatabase.CONFLICT_ABORT, ContentValues().apply {
                put("bookId", "book-c")
                put("sourceKey", "")
                put("rewindSeconds", 9)
                put("sleepTimerDefaultSeconds", 300)
                put("volumeBoostEnabled", 0)
                put("silenceSkipEnabled", 0)
                put("updatedAt", 10103L)
            }) != -1L)

            fun assertAllStored(
                snapshot: ManualWorkMergeSnapshot,
                expectedCounter: AchievementCounterEntity = globalCounter,
                expectedBookmark: BookmarkEntity = bookmarkA,
            ) {
                assertEquals(listOf(workA, workB, workC), snapshot.library.works.sortedWith(compareBy<WorkEntity> { it.id }))
                assertEquals(listOf(entryA, entryB, entryC, entryPaper), snapshot.library.entries.sortedWith(compareBy<LibraryEntryEntity> { it.id }))
                assertEquals(listOf(bookA, bookB, bookC), snapshot.library.books.map { AudiobookEntity(id = it.id, title = it.title, author = it.author, narrator = it.narrator, description = it.description, coverDrawableRes = it.coverDrawableRes, coverImageUrl = it.coverImageUrl, genre = it.genre, sourceUrl = it.sourceUrl, isDownloaded = it.isDownloaded, totalDurationSeconds = it.totalDurationSeconds, totalChapters = it.totalChapters, rating = it.rating, sourceTreeUri = it.sourceTreeUri) }.sortedWith(compareBy<AudiobookEntity> { it.id }))
                assertEquals(listOf(editionA, editionB, editionC, editionCatalog), snapshot.library.editions.sortedWith(compareBy<EditionEntity> { it.id }))
                assertEquals(listOf(sourceA, sourceB, sourceC), snapshot.library.sources.sortedWith(compareBy<SourceEntity> { it.id }))
                assertEquals(listOf(trackA, trackB, trackC), snapshot.library.tracks.sortedWith(compareBy<SourceTrackEntity> { it.id }))
                assertEquals(listOf(chapterA, chapterB, chapterC), snapshot.library.chapters.sortedWith(compareBy<ChapterEntity> { it.id }))
                assertEquals(listOf(progressA, progressB, progressC), snapshot.library.progress.sortedWith(compareBy<PlaybackProgressEntity> { it.editionId }))
                assertEquals(listOf(expectedBookmark, bookmarkB, bookmarkC), snapshot.library.bookmarks.sortedWith(compareBy<BookmarkEntity> { it.id }))
                assertEquals(listOf(settingsA, settingsB, settingsC), snapshot.library.settings.sortedWith(compareBy<EditionSettingsEntity> { it.bookId }.thenBy { it.sourceKey }))
                assertEquals(listOf(audioPassA, finishedPassB, repeatedPassB, paperPassA), snapshot.library.readthroughs.sortedWith(compareBy<ReadthroughEntity> { it.id }))
                assertEquals(listOf(eventA, eventB), snapshot.library.events.sortedWith(compareBy<PlaybackEventEntity> { it.id }))
                assertEquals(listOf(failureA), snapshot.library.failures.sortedWith(compareBy<PlaybackFailureEntity> { it.id }))
                assertEquals(listOf(submissionA), snapshot.library.submissions.sortedWith(compareBy<SubmissionStateEntity> { it.sourceId }))
                assertEquals(listOf(claimA, claimB, claimC), snapshot.catalog.workSources.sortedWith(compareBy<WorkSourceEntity> { it.id }))
                assertEquals(listOf(seriesMain), snapshot.catalog.series.sortedWith(compareBy<SeriesEntity> { it.id }))
                assertEquals(listOf(memberA, memberB), snapshot.catalog.seriesMembers.sortedWith(compareBy<SeriesMemberEntity> { it.workId }.thenBy { it.seriesId }))
                assertEquals(listOf(universeMain), snapshot.catalog.universes.sortedWith(compareBy<UniverseEntity> { it.id }))
                assertEquals(listOf(facetA, facetB), snapshot.catalog.workFacets.sortedWith(compareBy<WorkFacetEntity> { it.workId }))
                assertEquals(listOf(facetSeriesA, facetSeriesB), snapshot.catalog.facetSeries.sortedWith(compareBy<WorkFacetSeriesEntity> { it.workId }.thenBy { it.seriesId }))
                assertEquals(listOf(genreFantasy), snapshot.catalog.genres.sortedWith(compareBy<GenreFacetEntity> { it.id }))
                assertEquals(listOf(stateA, emptyStateA, stateB), snapshot.catalog.genreStates.sortedWith(compareBy<GenreAssertionStateEntity> { it.workId }.thenBy { it.sourceId }))
                assertEquals(listOf(genreClaimA, genreClaimB), snapshot.catalog.genreAssertions.sortedWith(compareBy<GenreAssertionEntity> { it.id }))
                assertEquals(listOf(workGenreA, workGenreB), snapshot.catalog.workGenres.sortedWith(compareBy<WorkGenreEntity> { it.workId }.thenBy { it.genreId }.thenBy { it.sourceId }))
                assertEquals(listOf(editionFacetA, editionFacetB, editionFacetCatalog), snapshot.catalog.editionFacets.sortedWith(compareBy<EditionFacetEntity> { it.editionId }))
                assertEquals(listOf(authorA, authorB), snapshot.catalog.authors.sortedWith(compareBy<AuthorFacetEntity> { it.id }))
                assertEquals(listOf(aliasA, aliasB), snapshot.catalog.authorAliases.sortedWith(compareBy<AuthorAliasEntity> { it.authorId }.thenBy { it.normalizedAlias }.thenBy { it.sourceId }))
                assertEquals(listOf(ftsA, ftsADuplicate, ftsB, ftsC), snapshot.catalog.ftsRows.sortedWith(compareBy<WorkSearchFtsEntity> { it.workId }.thenBy { it.title }.thenBy { it.author }.thenBy { it.series }.thenBy { it.narrator }))
                assertEquals(listOf(popularityA), snapshot.catalog.popularity.sortedWith(compareBy<PopularityAssertionEntity> { it.id }))
                assertEquals(listOf(vectorA, vectorC), snapshot.catalog.vectors.map { EmbeddingVectorEntity(it.workId, it.textHash, it.vector.copyBytes(), it.updatedAt) }.sortedWith(compareBy<EmbeddingVectorEntity> { it.workId }))
                assertEquals(listOf(chapterOrder, neverMatch), snapshot.personal.corrections.sortedWith(compareBy<CorrectionEntity> { it.mergeKey }.thenBy { it.kind }.thenBy { it.value }))
                assertEquals(listOf(tombstoneA), snapshot.personal.tombstones.sortedWith(compareBy<TombstoneEntity> { it.bookId }))
                assertEquals(listOf(preferenceA), snapshot.personal.preferences.sortedWith(compareBy<RecommendationPreferenceEntity> { it.kind }.thenBy { it.targetKey }))
                assertEquals(listOf(collectionMain), snapshot.personal.collections.sortedWith(compareBy<ListenerCollectionEntity> { it.id }))
                assertEquals(listOf(itemA, itemB), snapshot.personal.collectionItems.sortedWith(compareBy<ListenerCollectionItemEntity> { it.collectionId }.thenBy { it.bookId }))
                assertEquals(listOf(feedA, feedC), snapshot.personal.feedSnapshots.sortedWith(compareBy<FeedSnapshotEntity> { it.sourceId }.thenBy { it.feedKey }.thenBy { it.pageCursor }))
                assertEquals(listOf(globalStats), snapshot.retained.stats.sortedWith(compareBy<ListeningStatEntity> { it.dateIso }))
                assertEquals(listOf(globalSession), snapshot.retained.sessions.sortedWith(compareBy<PlaybackSessionEntity> { it.id }))
                assertEquals(listOf(globalAward), snapshot.retained.achievements.sortedWith(compareBy<AchievementEntity> { it.id }))
                assertEquals(listOf(globalFact), snapshot.retained.achievementFacts.sortedWith(compareBy<AchievementFactEntity> { it.key }))
                assertEquals(listOf(expectedCounter), snapshot.retained.achievementCounters.sortedWith(compareBy<AchievementCounterEntity> { it.key }))
                assertEquals(listOf(globalPerson), snapshot.retained.personBookmarks.sortedWith(compareBy<PersonBookmarkEntity> { it.kind }.thenBy { it.id }))
                assertEquals(listOf(globalMute), snapshot.retained.hiddenReviewers.sortedWith(compareBy<HiddenReviewerEntity> { it.authorName }))
                assertEquals(listOf(globalFriend), snapshot.retained.friendships.sortedWith(compareBy<FriendshipStateEntity> { it.pseudonym }))
                assertEquals(listOf(globalBlock), snapshot.retained.socialBlocks.sortedWith(compareBy<BlockEntity> { it.pseudonym }.thenBy { it.direction }))
            }
            fun assertPair(snapshot: ManualWorkMergeSnapshot) {
                assertEquals(listOf(workA, workB), snapshot.library.works.sortedWith(compareBy<WorkEntity> { it.id }))
                assertEquals(listOf(entryA, entryB, entryPaper), snapshot.library.entries.sortedWith(compareBy<LibraryEntryEntity> { it.id }))
                assertEquals(listOf(bookA, bookB), snapshot.library.books.map { AudiobookEntity(id = it.id, title = it.title, author = it.author, narrator = it.narrator, description = it.description, coverDrawableRes = it.coverDrawableRes, coverImageUrl = it.coverImageUrl, genre = it.genre, sourceUrl = it.sourceUrl, isDownloaded = it.isDownloaded, totalDurationSeconds = it.totalDurationSeconds, totalChapters = it.totalChapters, rating = it.rating, sourceTreeUri = it.sourceTreeUri) }.sortedWith(compareBy<AudiobookEntity> { it.id }))
                assertEquals(listOf(editionA, editionB, editionCatalog), snapshot.library.editions.sortedWith(compareBy<EditionEntity> { it.id }))
                assertEquals(listOf(sourceA, sourceB), snapshot.library.sources.sortedWith(compareBy<SourceEntity> { it.id }))
                assertEquals(listOf(trackA, trackB), snapshot.library.tracks.sortedWith(compareBy<SourceTrackEntity> { it.id }))
                assertEquals(listOf(chapterA, chapterB), snapshot.library.chapters.sortedWith(compareBy<ChapterEntity> { it.id }))
                assertEquals(listOf(progressA, progressB), snapshot.library.progress.sortedWith(compareBy<PlaybackProgressEntity> { it.editionId }))
                assertEquals(listOf(bookmarkA, bookmarkB), snapshot.library.bookmarks.sortedWith(compareBy<BookmarkEntity> { it.id }))
                assertEquals(listOf(settingsA, settingsB), snapshot.library.settings.sortedWith(compareBy<EditionSettingsEntity> { it.bookId }.thenBy { it.sourceKey }))
                assertEquals(listOf(audioPassA, finishedPassB, repeatedPassB, paperPassA), snapshot.library.readthroughs.sortedWith(compareBy<ReadthroughEntity> { it.id }))
                assertEquals(listOf(eventA, eventB), snapshot.library.events.sortedWith(compareBy<PlaybackEventEntity> { it.id }))
                assertEquals(listOf(failureA), snapshot.library.failures.sortedWith(compareBy<PlaybackFailureEntity> { it.id }))
                assertEquals(listOf(submissionA), snapshot.library.submissions.sortedWith(compareBy<SubmissionStateEntity> { it.sourceId }))
                assertEquals(listOf(claimA, claimB), snapshot.catalog.workSources.sortedWith(compareBy<WorkSourceEntity> { it.id }))
                assertEquals(listOf(seriesMain), snapshot.catalog.series.sortedWith(compareBy<SeriesEntity> { it.id }))
                assertEquals(listOf(memberA, memberB), snapshot.catalog.seriesMembers.sortedWith(compareBy<SeriesMemberEntity> { it.workId }.thenBy { it.seriesId }))
                assertEquals(listOf(universeMain), snapshot.catalog.universes.sortedWith(compareBy<UniverseEntity> { it.id }))
                assertEquals(listOf(facetA, facetB), snapshot.catalog.workFacets.sortedWith(compareBy<WorkFacetEntity> { it.workId }))
                assertEquals(listOf(facetSeriesA, facetSeriesB), snapshot.catalog.facetSeries.sortedWith(compareBy<WorkFacetSeriesEntity> { it.workId }.thenBy { it.seriesId }))
                assertEquals(listOf(genreFantasy), snapshot.catalog.genres.sortedWith(compareBy<GenreFacetEntity> { it.id }))
                assertEquals(listOf(stateA, emptyStateA, stateB), snapshot.catalog.genreStates.sortedWith(compareBy<GenreAssertionStateEntity> { it.workId }.thenBy { it.sourceId }))
                assertEquals(listOf(genreClaimA, genreClaimB), snapshot.catalog.genreAssertions.sortedWith(compareBy<GenreAssertionEntity> { it.id }))
                assertEquals(listOf(workGenreA, workGenreB), snapshot.catalog.workGenres.sortedWith(compareBy<WorkGenreEntity> { it.workId }.thenBy { it.genreId }.thenBy { it.sourceId }))
                assertEquals(listOf(editionFacetA, editionFacetB, editionFacetCatalog), snapshot.catalog.editionFacets.sortedWith(compareBy<EditionFacetEntity> { it.editionId }))
                assertEquals(listOf(authorA, authorB), snapshot.catalog.authors.sortedWith(compareBy<AuthorFacetEntity> { it.id }))
                assertEquals(listOf(aliasA, aliasB), snapshot.catalog.authorAliases.sortedWith(compareBy<AuthorAliasEntity> { it.authorId }.thenBy { it.normalizedAlias }.thenBy { it.sourceId }))
                assertEquals(listOf(ftsA, ftsADuplicate, ftsB), snapshot.catalog.ftsRows.sortedWith(compareBy<WorkSearchFtsEntity> { it.workId }.thenBy { it.title }.thenBy { it.author }.thenBy { it.series }.thenBy { it.narrator }))
                assertEquals(listOf(popularityA), snapshot.catalog.popularity.sortedWith(compareBy<PopularityAssertionEntity> { it.id }))
                assertEquals(listOf(vectorA), snapshot.catalog.vectors.map { EmbeddingVectorEntity(it.workId, it.textHash, it.vector.copyBytes(), it.updatedAt) }.sortedWith(compareBy<EmbeddingVectorEntity> { it.workId }))
                assertEquals(listOf(chapterOrder, neverMatch), snapshot.personal.corrections.sortedWith(compareBy<CorrectionEntity> { it.mergeKey }.thenBy { it.kind }.thenBy { it.value }))
                assertEquals(listOf(tombstoneA), snapshot.personal.tombstones.sortedWith(compareBy<TombstoneEntity> { it.bookId }))
                assertEquals(listOf(preferenceA), snapshot.personal.preferences.sortedWith(compareBy<RecommendationPreferenceEntity> { it.kind }.thenBy { it.targetKey }))
                assertEquals(listOf(collectionMain), snapshot.personal.collections.sortedWith(compareBy<ListenerCollectionEntity> { it.id }))
                assertEquals(listOf(itemA, itemB), snapshot.personal.collectionItems.sortedWith(compareBy<ListenerCollectionItemEntity> { it.collectionId }.thenBy { it.bookId }))
                assertEquals(listOf(feedA), snapshot.personal.feedSnapshots.sortedWith(compareBy<FeedSnapshotEntity> { it.sourceId }.thenBy { it.feedKey }.thenBy { it.pageCursor }))
                assertEquals(listOf(globalStats), snapshot.retained.stats.sortedWith(compareBy<ListeningStatEntity> { it.dateIso }))
                assertEquals(listOf(globalSession), snapshot.retained.sessions.sortedWith(compareBy<PlaybackSessionEntity> { it.id }))
                assertEquals(listOf(globalAward), snapshot.retained.achievements.sortedWith(compareBy<AchievementEntity> { it.id }))
                assertEquals(listOf(globalFact), snapshot.retained.achievementFacts.sortedWith(compareBy<AchievementFactEntity> { it.key }))
                assertEquals(listOf(globalCounter), snapshot.retained.achievementCounters.sortedWith(compareBy<AchievementCounterEntity> { it.key }))
                assertEquals(listOf(globalPerson), snapshot.retained.personBookmarks.sortedWith(compareBy<PersonBookmarkEntity> { it.kind }.thenBy { it.id }))
                assertEquals(listOf(globalMute), snapshot.retained.hiddenReviewers.sortedWith(compareBy<HiddenReviewerEntity> { it.authorName }))
                assertEquals(listOf(globalFriend), snapshot.retained.friendships.sortedWith(compareBy<FriendshipStateEntity> { it.pseudonym }))
                assertEquals(listOf(globalBlock), snapshot.retained.socialBlocks.sortedWith(compareBy<BlockEntity> { it.pseudonym }.thenBy { it.direction }))
            }

            assertAllStored(dao.readManualWorkMergeRows(listOf("work-a", "work-b", "work-c")))
            val preview = ManualWorkMerge(dao).prepareManualWorkMerge("work-a", "work-b")
            assertPair(preview.snapshot)
            assertEquals(2, preview.counts.works)
            assertEquals(3, preview.counts.entries)
            assertEquals(2, preview.counts.books)
            assertEquals(3, preview.counts.editions)
            assertEquals(2, preview.counts.sources)
            assertEquals(2, preview.counts.tracks)
            assertEquals(2, preview.counts.chapters)
            assertEquals(2, preview.counts.progress)
            assertEquals(2, preview.counts.bookmarks)
            assertEquals(2, preview.counts.settings)
            assertEquals(4, preview.counts.readthroughs)
            assertEquals(2, preview.counts.events)
            assertEquals(1, preview.counts.failures)
            assertEquals(1, preview.counts.submissions)
            assertEquals(2, preview.counts.workSources)
            assertEquals(1, preview.counts.series)
            assertEquals(2, preview.counts.seriesMembers)
            assertEquals(1, preview.counts.universes)
            assertEquals(2, preview.counts.workFacets)
            assertEquals(2, preview.counts.facetSeries)
            assertEquals(1, preview.counts.genres)
            assertEquals(3, preview.counts.genreStates)
            assertEquals(2, preview.counts.genreAssertions)
            assertEquals(2, preview.counts.workGenres)
            assertEquals(3, preview.counts.editionFacets)
            assertEquals(2, preview.counts.authors)
            assertEquals(2, preview.counts.authorAliases)
            assertEquals(3, preview.counts.ftsRows)
            assertEquals(1, preview.counts.popularity)
            assertEquals(1, preview.counts.vectors)
            assertEquals(2, preview.counts.corrections)
            assertEquals(1, preview.counts.tombstones)
            assertEquals(1, preview.counts.preferences)
            assertEquals(1, preview.counts.collections)
            assertEquals(2, preview.counts.collectionItems)
            assertEquals(1, preview.counts.feedSnapshots)
            assertEquals(1, preview.counts.retainedStats)
            assertEquals(1, preview.counts.retainedSessions)
            assertEquals(1, preview.counts.retainedAchievements)
            assertEquals(1, preview.counts.retainedAchievementFacts)
            assertEquals(1, preview.counts.retainedAchievementCounters)
            assertEquals(1, preview.counts.retainedPersonBookmarks)
            assertEquals(1, preview.counts.retainedHiddenReviewers)
            assertEquals(1, preview.counts.retainedFriendships)
            assertEquals(1, preview.counts.retainedSocialBlocks)
            assertEquals(setOf(
                ManualWorkMergeConflict.Tombstone(tombstoneA),
                ManualWorkMergeConflict.NeverMatch(neverMatch),
                ManualWorkMergeConflict.CanonicalAuthor(facetA, facetB),
                ManualWorkMergeConflict.SeriesPosition(memberA, memberB),
            ), preview.conflicts.toSet())
            assertEquals(4, preview.conflicts.size)
            assertEquals("manual-work-merge-v1:1800772f4361e9e17198a4f5e9743574a74877293c589fa9c0ce34cd675c4484", preview.fingerprint)
            assertEquals(setOf("work-a", "work-b"), preview.participants.map { it.work.id }.toSet())
            val first = preview.participants.single { it.work.id == "work-a" }
            assertEquals(setOf("edition-a", "edition-catalog-a"), first.editions.map { it.edition.id }.toSet())
            assertNull(first.editions.single { it.edition.id == "edition-catalog-a" }.bookId)
            assertAllStored(dao.readManualWorkMergeRows(listOf("work-a", "work-b", "work-c")))

            val copy = preview.snapshot.catalog.vectors.single().vector.copyBytes()
            copy[0] = 42
            assertArrayEquals(byteArrayOf(0, 1, -1, 127), preview.snapshot.catalog.vectors.single().vector.copyBytes())

            // Fixture changes happen outside prepare; FTS physical rowids are not its semantic identity.
            dao.deleteWorkSearchRows("work-a")
            dao.deleteWorkSearchRows("work-b")
            dao.insertWorkSearchRow(ftsB.workId, ftsB.title, ftsB.author, ftsB.series, ftsB.narrator)
            dao.insertWorkSearchRow(ftsA.workId, ftsA.title, ftsA.author, ftsA.series, ftsA.narrator)
            dao.insertWorkSearchRow(ftsADuplicate.workId, ftsADuplicate.title, ftsADuplicate.author, ftsADuplicate.series, ftsADuplicate.narrator)
            achievements.bumpCounter("global-counter")
            val samePair = ManualWorkMerge(dao).prepareManualWorkMerge("work-b", "work-a")
            assertEquals("manual-work-merge-v1:1800772f4361e9e17198a4f5e9743574a74877293c589fa9c0ce34cd675c4484", samePair.fingerprint)
            assertEquals(listOf(globalCounter), preview.snapshot.retained.achievementCounters)
            assertEquals(listOf(globalCounterAfter), samePair.snapshot.retained.achievementCounters)
            assertAllStored(
                dao.readManualWorkMergeRows(listOf("work-a", "work-b", "work-c")),
                expectedCounter = globalCounterAfter,
            )

            dao.insertBookmark(bookmarkAfter)
            val changedPair = ManualWorkMerge(dao).prepareManualWorkMerge("work-a", "work-b")
            assertEquals("manual-work-merge-v1:1bb045553d88bee7142cd718e1bad7bba418b1205b2734d1a681c03462d637ef", changedPair.fingerprint)
            assertEquals("manual-work-merge-v1:1800772f4361e9e17198a4f5e9743574a74877293c589fa9c0ce34cd675c4484", preview.fingerprint)
            assertEquals(listOf(bookmarkA, bookmarkB), preview.snapshot.library.bookmarks.sortedBy { it.id })
            assertAllStored(
                dao.readManualWorkMergeRows(listOf("work-a", "work-b", "work-c")),
                expectedCounter = globalCounterAfter,
                expectedBookmark = bookmarkAfter,
            )
        } finally {
            db.close()
        }
    }
}
