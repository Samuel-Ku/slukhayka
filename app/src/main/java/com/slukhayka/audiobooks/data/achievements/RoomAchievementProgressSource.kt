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
    fileReady: (String) -> Boolean = { File(it).let { file -> file.isFile && file.length() > 0L } },
    /**
     * #703 (T5) — the zone that decides "night" and "holiday".
     *
     * A parameter, not a global, so a test can pin it. Without that the awards
     * would depend on the machine running the tests, and "between 02:00 and
     * 04:00" would mean different instants in CI and on a phone.
     */
    private val zoneId: java.time.ZoneId = java.time.ZoneId.systemDefault()
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
    private val withGenres = combine(withBrowser, dao.observeGenreBookCounts()) { base, genres ->
        base.copy(genreCounts = genres.associate { it.genreId to it.works })
    }
    private val withSeries = combine(withGenres, dao.observeSeriesInLibrary()) { base, series ->
        base.copy(seriesInLibrary = series)
    }
    private val completionTimes = dao.observeCompletionTimes()
    private val sessionStartTimes = dao.observeSessionStartTimes()
    private val countersWithShape = combine(withSeries, completionTimes, sessionStartTimes) {
            base, completions, sessions ->
        base.copy(
            nightCompletions = completions.count { hourOf(it) in 2..3 }.toLong(),
            owlLarkBalance = minOf(
                sessions.count { hourOf(it) < 6 },
                sessions.count { hourOf(it) >= 22 }
            ).toLong(),
            holidayCompletions = completions.count(::isHoliday).toLong()
        )
    }

    private fun hourOf(epochMillis: Long): Int =
        java.time.Instant.ofEpochMilli(epochMillis).atZone(zoneId).hour

    /**
     * New Year and Christmas. Both Christmas dates are included on purpose: this
     * is a Ukrainian app, and 7 January is as much Christmas here as 25
     * December. The listener's own zone decides which calendar day an instant
     * belongs to.
     */
    private fun isHoliday(epochMillis: Long): Boolean {
        val date = java.time.Instant.ofEpochMilli(epochMillis).atZone(zoneId).toLocalDate()
        return (date.monthValue == 1 && (date.dayOfMonth == 1 || date.dayOfMonth == 7)) ||
            (date.monthValue == 12 && date.dayOfMonth == 25)
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
