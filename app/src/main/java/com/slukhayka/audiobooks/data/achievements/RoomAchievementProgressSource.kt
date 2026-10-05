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
    // #700/#701/#702 — the rest of the snapshot, folded in ONE FIELD AT A TIME.
    //
    // `combine` stops at five arguments, and the previous version nested pairs
    // three deep to work around that (`prev.first.first.first`). It compiled,
    // but it was unreadable and every new metric made it worse. Each step below
    // adds its fields to a growing copy instead, so adding the next metric is
    // one obvious line.
    private val shape = combine(dao.observeShortCompletedBooks(), dao.observeEpicCompletedBooks()) { short, epic ->
        short to epic
    }
    private val speeds = combine(dao.observeFastBooks(), dao.observeSlowBooks()) { fast, slow -> fast to slow }
    private val marks = combine(dao.observeBookmarks(), dao.observeNotes()) { marks, notes -> marks to notes }
    private val returns = combine(dao.observeTimerStops(), dao.observeRelistens()) { stops, relistens -> stops to relistens }

    private val withShape = combine(counters, shape) { base, (short, epic) ->
        base.copy(shortCompletedBooks = short, epicCompletedBooks = epic)
    }
    private val withSpeeds = combine(withShape, speeds) { base, (fast, slow) ->
        base.copy(fastBooks = fast, slowBooks = slow)
    }
    private val withMarks = combine(withSpeeds, marks) { base, (marks, notes) ->
        base.copy(bookmarks = marks, notes = notes)
    }
    private val withReturns = combine(withMarks, returns) { base, (stops, relistens) ->
        base.copy(timerStops = stops, relistens = relistens)
    }
    private val withDoors = combine(withReturns, dao.observeUsedSourceDoors()) { base, doors ->
        base.copy(usedSourceDoors = doors)
    }
    private val withLanguages = combine(withDoors, dao.observeKnownLanguages()) { base, languages ->
        base.copy(knownLanguages = languages)
    }
    private val withBrowser = combine(withLanguages, dao.observeBrowserBooks()) { base, browser ->
        base.copy(browserBooks = browser)
    }
    private val countersWithShape = combine(withBrowser, dao.observeGenreBookCounts()) { base, genres ->
        base.copy(genreCounts = genres.associate { it.genreId to it.works })
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
