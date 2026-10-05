package com.slukhayka.audiobooks.data.achievements

import com.slukhayka.audiobooks.data.db.AchievementDao
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** Composition adapter over actual local data; the evaluator owns no Room or source registry. */
class RoomAchievementProgressSource(
    dao: AchievementDao,
    store: AchievementStore,
    registeredSourceIds: Set<String>,
    fileReady: (String) -> Boolean = { File(it).let { file -> file.isFile && file.length() > 0L } }
) : AchievementProgressSource {
    private val counters = combine(dao.observeExplicitBooks(), dao.observeVerifiedListeningMillis(),
        dao.observeNotInterestedChoices(), dao.observeCompletedBooks()) { books, millis, choices, completed ->
        AchievementProgress(explicitBooks = books, verifiedListeningMillis = millis,
            notInterestedChoices = choices, completedBooks = completed)
    }
    // #700 (T2) — the duration bands. Kept in their own flow and copied in, the
    // way `aggregates` already does: `combine` has no six-argument overload, and
    // nesting pairs is the established shape here.
    private val shape = combine(dao.observeShortCompletedBooks(), dao.observeEpicCompletedBooks()) { short, epic ->
        short to epic
    }
    private val speeds = combine(dao.observeFastBooks(), dao.observeSlowBooks()) { fast, slow ->
        fast to slow
    }
    private val marks = combine(dao.observeBookmarks(), dao.observeNotes()) { marks, notes ->
        marks to notes
    }
    private val returns = combine(dao.observeTimerStops(), dao.observeRelistens()) { stops, relistens ->
        stops to relistens
    }
    private val countersWithShape =
        combine(counters, shape, speeds, marks, returns) {
                base, (short, epic), (fast, slow), (marks, notes), (stops, relistens) ->
            base.copy(
                shortCompletedBooks = short,
                epicCompletedBooks = epic,
                fastBooks = fast,
                slowBooks = slow,
                bookmarks = marks,
                notes = notes,
                timerStops = stops,
                relistens = relistens
            )
        }
    // Files are inspected only when the track/topology rows change, never on every listening tick.
    private val downloads = dao.observeDownloadedTracks().distinctUntilChanged()
        .map { DownloadedBookProof.count(it, fileReady) }.flowOn(Dispatchers.IO)
    private val topology = combine(downloads, dao.observeKnownSeriesMemberships()) { downloaded, members ->
        downloaded to members.map { AchievementSeriesMembership(it.seriesId, it.workId, it.position) }.toSet()
    }
    private val aggregates = combine(countersWithShape, topology) { counters, topology ->
        counters.copy(downloadedBooks = topology.first, registeredSourceIds = registeredSourceIds,
            knownSeriesMemberships = topology.second)
    }
    private val local = LocalAchievementProgressSource(aggregates, store.observeFacts())
    override fun observe() = local.observe().distinctUntilChanged()
}
