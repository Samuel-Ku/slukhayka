package com.slukhayka.audiobooks.data.achievements

import com.slukhayka.audiobooks.data.collections.CollectionList
import com.slukhayka.audiobooks.data.universe.UniverseList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * #1589 (lever 2) — the four facts the achievements snapshot derives from the
 * listener's OWN library and the curated lists.
 *
 * The derivations walk the whole library and match it against every curated
 * universe and collection; [AchievementSnapshotSource][com.slukhayka.audiobooks.data.achievements.AchievementSnapshotSource]
 * used to run them inside a `combine` whose other arm ticks on every written
 * playback second. The library list itself changes only when the listener's
 * books or their completion events change, so an unchanged list must not be
 * re-matched at 1 Hz.
 *
 * This value separates "what the library says" from "what the snapshot knows":
 * equal facts mean the snapshot has nothing new to say about the library.
 */
data class OwnLibraryFacts(
    val completedSeries: Long,
    val orderedSeries: Long,
    val universeBooks: Long,
    val passedCollections: Long
)

/** The matching rules, composed exactly once so both the flow and its witness share them. */
internal fun deriveOwnLibraryFacts(
    own: List<OwnLibraryBook>,
    curatedUniverses: List<UniverseList>,
    curatedCollections: List<CollectionList>
): OwnLibraryFacts = OwnLibraryFacts(
    completedSeries = PersonalSeries.finished(own),
    orderedSeries = PersonalSeries.inOrder(own),
    universeBooks = UniverseBreadth.largest(own, curatedUniverses),
    passedCollections = CollectionCompletion.passed(own, curatedCollections)
)

/**
 * #1589 (lever 2) — derive [OwnLibraryFacts] once per DISTINCT library
 * snapshot, not once per emission of whatever flow is combined next to it.
 *
 * [distinctUntilChanged] compares the `OwnLibraryBook` values structurally (a
 * data class), so a re-queried but identical list — Room re-runs the query on
 * every invalidation — is swallowed before any matching happens. The second
 * [distinctUntilChanged] keeps a changed row that does not change the facts
 * (e.g. a completion event for a book outside every curated list) from waking
 * the snapshot downstream.
 *
 * [derive] is a parameter so the circuit can be proven without a counter in
 * production code: the witness hands in a counting derivation.
 */
internal fun Flow<List<OwnLibraryBook>>.ownLibraryFacts(
    derive: (List<OwnLibraryBook>) -> OwnLibraryFacts
): Flow<OwnLibraryFacts> = distinctUntilChanged().map(derive).distinctUntilChanged()
