package com.slukhayka.audiobooks.ui.screens

import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.EditionEntity
import com.slukhayka.audiobooks.data.db.LibraryEntryEntity
import com.slukhayka.audiobooks.data.db.PersonBookmarkEntity
import com.slukhayka.audiobooks.data.db.PlaybackProgressEntity
import com.slukhayka.audiobooks.data.db.WorkEntity
import com.slukhayka.audiobooks.data.personbookmarks.PersonNewArrivals
import com.slukhayka.audiobooks.data.recommend.RecommendationEngine
import com.slukhayka.audiobooks.data.source.GlobalSearchResult
import com.slukhayka.audiobooks.ui.DurationBooks
import com.slukhayka.audiobooks.ui.durationBooksFrom
import com.slukhayka.audiobooks.ui.library.PersonalCycle
import com.slukhayka.audiobooks.ui.library.PersonalCycles
import com.slukhayka.audiobooks.ui.library.SimilarCycle
import com.slukhayka.audiobooks.ui.library.SimilarCycles
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * #1743 — the Огляд projections that depend only on data, never on scroll.
 *
 * The screen used to compute all of these in `remember { … }` on the
 * composition thread. On a cold catalogue the first scroll caught two
 * 370–390 ms frames (main Running 400/450 ms, GC 56 ms): a catalogue refresh
 * landing mid-scroll recomputed the whole projection on main.
 *
 * The builders stay pure and unchanged; this class owns only the thread
 * hand-off. The screen receives the already-projected lists, and the flow
 * keeps the previous value while a new one is computed, so a refresh neither
 * empties a shelf nor adds a spinner. Empty input still means "no block" —
 * exactly what the screen did before.
 *
 * The builders are injectable so the #1743 test can count calls and record
 * the thread they ran on without reaching into production internals.
 */
class HomeProjections(
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val projectPersonNewArrivals: (
        bookmarks: List<PersonBookmarkEntity>,
        works: List<WorkEntity>,
        editions: List<EditionEntity>,
        unifiedCatalog: List<GlobalSearchResult>,
        libraryEntries: List<LibraryEntryEntity>
    ) -> PersonNewArrivals.CatalogProjection = PersonNewArrivals::projectCatalog,
    private val projectDurationBooks: (List<AudiobookEntity>) -> DurationBooks = ::durationBooksFrom,
    private val projectPersonalCycles: (
        libraryBooks: List<AudiobookEntity>,
        progress: List<PlaybackProgressEntity>,
        works: List<WorkEntity>,
        recommendations: List<RecommendationEngine.Recommendation>
    ) -> List<PersonalCycle> = { libraryBooks, progress, works, recommendations ->
        PersonalCycles.build(libraryBooks, progress, works, recommendations)
    },
    private val projectSimilarCycles: (
        picks: List<RecommendationEngine.Recommendation>,
        works: List<WorkEntity>,
        ownCycleTitles: Collection<String>
    ) -> List<SimilarCycle> = { picks, works, ownCycleTitles ->
        SimilarCycles.build(picks, works, ownCycleTitles)
    }
) {
    suspend fun peopleNewArrivals(
        bookmarks: List<PersonBookmarkEntity>,
        works: List<WorkEntity>,
        editions: List<EditionEntity>,
        unifiedCatalog: List<GlobalSearchResult>,
        libraryEntries: List<LibraryEntryEntity>
    ): PersonNewArrivals.CatalogProjection = withContext(dispatcher) {
        projectPersonNewArrivals(bookmarks, works, editions, unifiedCatalog, libraryEntries)
    }

    suspend fun durationBooks(books: List<AudiobookEntity>): DurationBooks =
        withContext(dispatcher) { projectDurationBooks(books) }

    suspend fun cycleShelves(
        libraryBooks: List<AudiobookEntity>,
        progress: List<PlaybackProgressEntity>,
        works: List<WorkEntity>,
        recommendations: List<RecommendationEngine.Recommendation>
    ): HomeCycleShelves = withContext(dispatcher) {
        val personal = projectPersonalCycles(libraryBooks, progress, works, recommendations)
        // SimilarCycles excludes the listener's own cycle titles, so both
        // tiers are built in one pass — the same relationship the two
        // `remember` blocks had on the screen.
        HomeCycleShelves(
            personal = personal,
            similar = projectSimilarCycles(
                recommendations,
                works,
                personal.map { it.title }
            )
        )
    }
}

/** The two «Ваші цикли» tiers, projected together (#1743). */
data class HomeCycleShelves(
    val personal: List<PersonalCycle> = emptyList(),
    val similar: List<SimilarCycle> = emptyList()
)
