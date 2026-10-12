package com.slukhayka.audiobooks.data.merge

import com.slukhayka.audiobooks.data.db.*
import java.util.Collections

/** Typed Room54 values. Physical SQLite before-images are a separate Undo contract. */
class ManualWorkMergeSnapshot internal constructor(
    rootWorkIds: List<String>,
    val library: ManualWorkMergeLibraryRows,
    val catalog: ManualWorkMergeCatalogRows,
    val personal: ManualWorkMergePersonalRows,
    val retained: ManualWorkMergeRetainedRows,
    conflicts: List<ManualWorkMergeConflict>,
) {
    val rootWorkIds: List<String> = immutableMergeList(rootWorkIds)
    val conflicts: List<ManualWorkMergeConflict> = immutableMergeList(conflicts)
}

class ManualWorkMergeLibraryRows internal constructor(
    works: List<WorkEntity>,
    entries: List<LibraryEntryEntity>,
    books: List<ManualWorkMergeBookRow>,
    editions: List<EditionEntity>,
    sources: List<SourceEntity>,
    tracks: List<SourceTrackEntity>,
    chapters: List<ChapterEntity>,
    progress: List<PlaybackProgressEntity>,
    bookmarks: List<BookmarkEntity>,
    settings: List<EditionSettingsEntity>,
    readthroughs: List<ReadthroughEntity>,
    events: List<PlaybackEventEntity>,
    failures: List<PlaybackFailureEntity>,
    submissions: List<SubmissionStateEntity>,
) {
    val works: List<WorkEntity> = immutableMergeList(works)
    val entries: List<LibraryEntryEntity> = immutableMergeList(entries)
    val books: List<ManualWorkMergeBookRow> = immutableMergeList(books)
    val editions: List<EditionEntity> = immutableMergeList(editions)
    val sources: List<SourceEntity> = immutableMergeList(sources)
    val tracks: List<SourceTrackEntity> = immutableMergeList(tracks)
    val chapters: List<ChapterEntity> = immutableMergeList(chapters)
    val progress: List<PlaybackProgressEntity> = immutableMergeList(progress)
    val bookmarks: List<BookmarkEntity> = immutableMergeList(bookmarks)
    val settings: List<EditionSettingsEntity> = immutableMergeList(settings)
    val readthroughs: List<ReadthroughEntity> = immutableMergeList(readthroughs)
    val events: List<PlaybackEventEntity> = immutableMergeList(events)
    val failures: List<PlaybackFailureEntity> = immutableMergeList(failures)
    val submissions: List<SubmissionStateEntity> = immutableMergeList(submissions)
}

class ManualWorkMergeCatalogRows internal constructor(
    workSources: List<WorkSourceEntity>,
    series: List<SeriesEntity>,
    seriesMembers: List<SeriesMemberEntity>,
    universes: List<UniverseEntity>,
    workFacets: List<WorkFacetEntity>,
    facetSeries: List<WorkFacetSeriesEntity>,
    genres: List<GenreFacetEntity>,
    genreStates: List<GenreAssertionStateEntity>,
    genreAssertions: List<GenreAssertionEntity>,
    workGenres: List<WorkGenreEntity>,
    editionFacets: List<EditionFacetEntity>,
    authors: List<AuthorFacetEntity>,
    authorAliases: List<AuthorAliasEntity>,
    ftsRows: List<WorkSearchFtsEntity>,
    popularity: List<PopularityAssertionEntity>,
    vectors: List<ManualWorkMergeVectorRow>,
) {
    val workSources: List<WorkSourceEntity> = immutableMergeList(workSources)
    val series: List<SeriesEntity> = immutableMergeList(series)
    val seriesMembers: List<SeriesMemberEntity> = immutableMergeList(seriesMembers)
    val universes: List<UniverseEntity> = immutableMergeList(universes)
    val workFacets: List<WorkFacetEntity> = immutableMergeList(workFacets)
    val facetSeries: List<WorkFacetSeriesEntity> = immutableMergeList(facetSeries)
    val genres: List<GenreFacetEntity> = immutableMergeList(genres)
    val genreStates: List<GenreAssertionStateEntity> = immutableMergeList(genreStates)
    val genreAssertions: List<GenreAssertionEntity> = immutableMergeList(genreAssertions)
    val workGenres: List<WorkGenreEntity> = immutableMergeList(workGenres)
    val editionFacets: List<EditionFacetEntity> = immutableMergeList(editionFacets)
    val authors: List<AuthorFacetEntity> = immutableMergeList(authors)
    val authorAliases: List<AuthorAliasEntity> = immutableMergeList(authorAliases)
    val ftsRows: List<WorkSearchFtsEntity> = immutableMergeList(ftsRows)
    val popularity: List<PopularityAssertionEntity> = immutableMergeList(popularity)
    val vectors: List<ManualWorkMergeVectorRow> = immutableMergeList(vectors)
}

class ManualWorkMergePersonalRows internal constructor(
    corrections: List<CorrectionEntity>,
    tombstones: List<TombstoneEntity>,
    preferences: List<RecommendationPreferenceEntity>,
    collections: List<ListenerCollectionEntity>,
    collectionItems: List<ListenerCollectionItemEntity>,
    feedSnapshots: List<FeedSnapshotEntity>,
) {
    val corrections: List<CorrectionEntity> = immutableMergeList(corrections)
    val tombstones: List<TombstoneEntity> = immutableMergeList(tombstones)
    val preferences: List<RecommendationPreferenceEntity> = immutableMergeList(preferences)
    val collections: List<ListenerCollectionEntity> = immutableMergeList(collections)
    val collectionItems: List<ListenerCollectionItemEntity> = immutableMergeList(collectionItems)
    val feedSnapshots: List<FeedSnapshotEntity> = immutableMergeList(feedSnapshots)
}

class ManualWorkMergeRetainedRows internal constructor(
    stats: List<ListeningStatEntity>,
    sessions: List<PlaybackSessionEntity>,
    achievements: List<AchievementEntity>,
    achievementFacts: List<AchievementFactEntity>,
    achievementCounters: List<AchievementCounterEntity>,
    personBookmarks: List<PersonBookmarkEntity>,
    hiddenReviewers: List<HiddenReviewerEntity>,
    friendships: List<FriendshipStateEntity>,
    socialBlocks: List<BlockEntity>,
) {
    val stats: List<ListeningStatEntity> = immutableMergeList(stats)
    val sessions: List<PlaybackSessionEntity> = immutableMergeList(sessions)
    val achievements: List<AchievementEntity> = immutableMergeList(achievements)
    val achievementFacts: List<AchievementFactEntity> = immutableMergeList(achievementFacts)
    val achievementCounters: List<AchievementCounterEntity> = immutableMergeList(achievementCounters)
    val personBookmarks: List<PersonBookmarkEntity> = immutableMergeList(personBookmarks)
    val hiddenReviewers: List<HiddenReviewerEntity> = immutableMergeList(hiddenReviewers)
    val friendships: List<FriendshipStateEntity> = immutableMergeList(friendships)
    val socialBlocks: List<BlockEntity> = immutableMergeList(socialBlocks)
}

data class ManualWorkMergeCounts(
    val works: Int,
    val entries: Int,
    val books: Int,
    val editions: Int,
    val sources: Int,
    val tracks: Int,
    val chapters: Int,
    val progress: Int,
    val bookmarks: Int,
    val settings: Int,
    val readthroughs: Int,
    val events: Int,
    val failures: Int,
    val submissions: Int,
    val workSources: Int,
    val series: Int,
    val seriesMembers: Int,
    val universes: Int,
    val workFacets: Int,
    val facetSeries: Int,
    val genres: Int,
    val genreStates: Int,
    val genreAssertions: Int,
    val workGenres: Int,
    val editionFacets: Int,
    val authors: Int,
    val authorAliases: Int,
    val ftsRows: Int,
    val popularity: Int,
    val vectors: Int,
    val corrections: Int,
    val tombstones: Int,
    val preferences: Int,
    val collections: Int,
    val collectionItems: Int,
    val feedSnapshots: Int,
    val retainedStats: Int,
    val retainedSessions: Int,
    val retainedAchievements: Int,
    val retainedAchievementFacts: Int,
    val retainedAchievementCounters: Int,
    val retainedPersonBookmarks: Int,
    val retainedHiddenReviewers: Int,
    val retainedFriendships: Int,
    val retainedSocialBlocks: Int,
) {
    internal companion object {
        fun from(snapshot: ManualWorkMergeSnapshot) = ManualWorkMergeCounts(
            works = snapshot.library.works.size,
            entries = snapshot.library.entries.size,
            books = snapshot.library.books.size,
            editions = snapshot.library.editions.size,
            sources = snapshot.library.sources.size,
            tracks = snapshot.library.tracks.size,
            chapters = snapshot.library.chapters.size,
            progress = snapshot.library.progress.size,
            bookmarks = snapshot.library.bookmarks.size,
            settings = snapshot.library.settings.size,
            readthroughs = snapshot.library.readthroughs.size,
            events = snapshot.library.events.size,
            failures = snapshot.library.failures.size,
            submissions = snapshot.library.submissions.size,
            workSources = snapshot.catalog.workSources.size,
            series = snapshot.catalog.series.size,
            seriesMembers = snapshot.catalog.seriesMembers.size,
            universes = snapshot.catalog.universes.size,
            workFacets = snapshot.catalog.workFacets.size,
            facetSeries = snapshot.catalog.facetSeries.size,
            genres = snapshot.catalog.genres.size,
            genreStates = snapshot.catalog.genreStates.size,
            genreAssertions = snapshot.catalog.genreAssertions.size,
            workGenres = snapshot.catalog.workGenres.size,
            editionFacets = snapshot.catalog.editionFacets.size,
            authors = snapshot.catalog.authors.size,
            authorAliases = snapshot.catalog.authorAliases.size,
            ftsRows = snapshot.catalog.ftsRows.size,
            popularity = snapshot.catalog.popularity.size,
            vectors = snapshot.catalog.vectors.size,
            corrections = snapshot.personal.corrections.size,
            tombstones = snapshot.personal.tombstones.size,
            preferences = snapshot.personal.preferences.size,
            collections = snapshot.personal.collections.size,
            collectionItems = snapshot.personal.collectionItems.size,
            feedSnapshots = snapshot.personal.feedSnapshots.size,
            retainedStats = snapshot.retained.stats.size,
            retainedSessions = snapshot.retained.sessions.size,
            retainedAchievements = snapshot.retained.achievements.size,
            retainedAchievementFacts = snapshot.retained.achievementFacts.size,
            retainedAchievementCounters = snapshot.retained.achievementCounters.size,
            retainedPersonBookmarks = snapshot.retained.personBookmarks.size,
            retainedHiddenReviewers = snapshot.retained.hiddenReviewers.size,
            retainedFriendships = snapshot.retained.friendships.size,
            retainedSocialBlocks = snapshot.retained.socialBlocks.size,
        )
    }
}

/** Only persisted metadata; mutable @Ignore UI fields of AudiobookEntity are absent. */
data class ManualWorkMergeBookRow(
    val id: String,
    val title: String,
    val author: String,
    val narrator: String,
    val description: String,
    val coverDrawableRes: Int,
    val coverImageUrl: String?,
    val genre: String,
    val sourceUrl: String,
    val isDownloaded: Boolean,
    val totalDurationSeconds: Long,
    val totalChapters: Int,
    val rating: Float,
    val sourceTreeUri: String?,
)

/** Neither the Room-owned input array nor the held snapshot bytes escape. */
class ManualWorkMergeBytes(bytes: ByteArray) {
    private val bytes: ByteArray = bytes.copyOf()
    fun copyBytes(): ByteArray = bytes.copyOf()
    override fun equals(other: Any?): Boolean = other is ManualWorkMergeBytes && bytes.contentEquals(other.bytes)
    override fun hashCode(): Int = bytes.contentHashCode()
}

data class ManualWorkMergeVectorRow(
    val workId: String,
    val textHash: String,
    val vector: ManualWorkMergeBytes,
    val updatedAt: Long,
)

/** Raw blockers and unresolved evidence; none chooses a canonical Work or applies a merge. */
sealed interface ManualWorkMergeConflict {
    data class Tombstone(val row: TombstoneEntity) : ManualWorkMergeConflict
    data class NeverMatch(val row: CorrectionEntity) : ManualWorkMergeConflict
    data class CanonicalAuthor(val first: WorkFacetEntity, val second: WorkFacetEntity) : ManualWorkMergeConflict
    data class SeriesPosition(val first: SeriesMemberEntity, val second: SeriesMemberEntity) : ManualWorkMergeConflict
    data class UnresolvedReference(val table: String, val rowKey: String, val reference: String, val id: String) : ManualWorkMergeConflict
    data class AmbiguousAnchor(val table: String, val rowKey: String, val firstWorkId: String, val secondWorkId: String) : ManualWorkMergeConflict
    data class AmbiguousNamespace(val editionId: String, val anchorId: String) : ManualWorkMergeConflict
    data class MultipleLibraryEntries(val editionId: String, val firstBookId: String, val secondBookId: String) : ManualWorkMergeConflict
    data class DuplicateWorkKey(val first: WorkEntity, val second: WorkEntity) : ManualWorkMergeConflict
    data class OpaqueDocument(val table: String, val rowKey: String) : ManualWorkMergeConflict
}

internal fun <T> immutableMergeList(rows: List<T>): List<T> = Collections.unmodifiableList(rows.toList())
