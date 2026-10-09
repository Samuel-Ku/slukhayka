package com.slukhayka.audiobooks.data.achievements

import com.slukhayka.audiobooks.data.db.AchievementDao
import com.slukhayka.audiobooks.data.db.ListeningStatEntity
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** Composition adapter over actual local data; the evaluator owns no Room or source registry. */
class RoomAchievementProgressSource(
    dao: AchievementDao,
    store: AchievementStore,
    registeredSourceIds: Set<String>,
    /**
     * #1175 (US42) — the Source Registry's appearance date, as a reader.
     *
     * A parameter for the same reason [zoneId] is one: the date lives in the
     * registry (ADR-0038), and a test has to be able to pin a DATED source
     * while none of the fifteen real sources may be given an invented date
     * (#1175). Production reads the registry, which answers unknown ids with
     * null — an unknown date is never read as today (ADR-0014).
     */
    private val appearedOnOf: (String) -> LocalDate? =
        com.slukhayka.audiobooks.data.source.SourceRegistry::appearedOn,
    fileReady: (String) -> Boolean = { File(it).let { file -> file.isFile && file.length() > 0L } },
    /**
     * #703 (T5) — the zone that decides "night" and "holiday".
     *
     * A parameter, not a global, so a test can pin it. Without that the awards
     * would depend on the machine running the tests, and "between 02:00 and
     * 04:00" would mean different instants in CI and on a phone.
     */
    private val zoneId: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    /**
     * #1174 (друга смуга, US28) — the books carrying the «покинуто» mark right
     * now, as the mark's OWN flow
     * ([com.slukhayka.audiobooks.data.entries.AbandonedBooks.observeAbandonedBookIds]).
     *
     * Injected rather than re-queried here for the reason the «завершено» edge
     * was moved into one function: a second copy of "what counts as abandoned"
     * would let the badge and the award drift apart. Production passes that
     * flow, so both surfaces read ONE query; the default is the honest "nothing
     * is abandoned" of a caller that writes no readthroughs, never a claim
     * about the listener (`NeverAbandonAwardTest` wires the real one).
     */
    private val abandonedBookIds: Flow<Set<String>> = flowOf(emptySet())
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
    private val withSecondWind = combine(withReturns, dao.observeBooksFinishedTwice()) { base, twice ->
        base.copy(booksFinishedTwice = twice)
    }
    private val withDoors = combine(withSecondWind, dao.observeUsedSourceDoors()) { base, doors ->
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
    private val withTimes = combine(withSeries, completionTimes, sessionStartTimes) {
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
    private val ages = combine(dao.observeVintageCompletions(), dao.observeReturnsAfterBreak()) {
            vintage, returns ->
        vintage to returns
    }
    private val withAges = combine(withTimes, ages) { base, (vintage, returns) ->
        base.copy(vintageCompletions = vintage, returnsAfterBreak = returns)
    }
    private val countersWithShape = combine(withAges, dao.observeLateCompletions()) { base, late ->
        base.copy(lateCompletions = late)
    }
    // #701 — the two awards the ticket's own tail added on top of the merged T3
    // slices: the series RUN (five in a row) and the English start. Each is one
    // more field on the growing copy, exactly like every step above.
    private val withSeriesRun = combine(countersWithShape, dao.observeSeriesCompletions()) {
            base, completions ->
        base.copy(longestSeriesRun = SeriesRun.longest(completions))
    }
    private val withEnglishStart = combine(withSeriesRun, dao.observeStartLanguages()) { base, claims ->
        base.copy(englishStartBooks = EnglishStart.count(claims))
    }
    // #1166 (T8) — the day sequence behind the regularity awards. The rows are
    // mapped once here, so the arithmetic itself stays pure (`ListeningRhythm`).
    private val rhythmDays = dao.observeListeningDays()
        .map { rows -> rows.mapNotNull(::listeningDay) }
        .distinctUntilChanged()
    private val withRhythm = combine(withEnglishStart, rhythmDays) { base, days ->
        base.copy(
            bestDayMillis = ListeningRhythm.bestDayMillis(days),
            longestDayStreak = ListeningRhythm.longestStreak(days).toLong(),
            bestMonthDays = ListeningRhythm.bestMonthDays(days).toLong(),
            mondaysListened = ListeningRhythm.mondays(days).toLong(),
            // #1166 (T8, story 13) — «Слухацький рік» reads the SAME day
            // sequence: one read of `listening_stats`, one rule for what a day
            // with listening is (`ListeningRhythm.DAY_MILLIS`).
            listeningDays = ListeningRhythm.listeningDays(days).toLong()
        )
    }

    // #1183 (T9b) — the measurement layer's own rows (#1173): the longest
    // session and the longest OFFLINE one (one query), plus every session start
    // for «Світанок». Both answers move only when a session really grows or
    // appears, while the table is invalidated on every written tick, so an equal
    // repeat stops here instead of rebuilding the snapshot above — the same
    // guard the download proof uses for its file inspection.
    private val sessionExtremes = dao.observeLongestSessions().distinctUntilChanged()
    private val sessionStarts = dao.observePlaybackSessionStarts().distinctUntilChanged()
    private val withSessions = combine(withRhythm, sessionExtremes, sessionStarts) {
            base, extremes, starts ->
        base.copy(
            longestSessionMillis = extremes.longestSessionMillis,
            longestOfflineSessionMillis = extremes.longestOfflineSessionMillis,
            morningDays = morningDays(starts)
        )
    }
    private val withMeasuredHours = combine(
        withSessions,
        dao.observeOfflineListeningMillis(),
        dao.observeCastListeningMillis(),
        dao.observeNightListeningMillis()
    ) { base, offline, cast, night ->
        base.copy(offlineMillis = offline, castMillis = cast, nightMillis = night)
    }
    private val withArms = combine(
        withMeasuredHours,
        dao.observeCounter(AchievementCounter.END_OF_CHAPTER_ARM)
    ) { base, arms ->
        base.copy(endOfChapterArms = arms)
    }
    // #1175 (US42) — the arrivals behind «Нова хвиля». The window is decided in
    // Kotlin because the other end of the pair lives in the Source Registry,
    // not in Room; the injected reader keeps the date a fact and never a guess.
    private val withNewWave = combine(withArms, dao.observeSourceArrivals()) { base, arrivals ->
        base.copy(newWaveBooks = NewWave.books(arrivals, appearedOnOf, zoneId))
    }
    // #1174 (друга смуга, US28) — the «покинуто» marks standing right now. The
    // SAME flow feeds the library badge, so the award and the badge can never
    // disagree about which books are abandoned; the count is per BOOK, and the
    // mark owner already answers that way.
    private val withAbandoned = combine(withNewWave, abandonedBookIds) { base, marked ->
        base.copy(abandonedBooks = marked.size.toLong())
    }

    /**
     * A row whose date cannot be read is not a day we can count, so it is
     * skipped rather than guessed (ADR-0014). `dateIso` is written as
     * `yyyy-MM-dd` by the recorder, so this only drops genuinely broken rows.
     */
    private fun listeningDay(row: ListeningStatEntity): ListeningRhythm.Day? =
        runCatching { LocalDate.parse(row.dateIso) }.getOrNull()
            ?.let { ListeningRhythm.Day(it, row.verifiedListenedMillis) }

    private fun hourOf(epochMillis: Long): Int =
        java.time.Instant.ofEpochMilli(epochMillis).atZone(zoneId).hour

    /**
     * #1183 (T9b) — «Світанок» counts MORNINGS, not sessions: the local date of
     * a session that STARTED between 06:00 and 08:00, each date once.
     *
     * Five short breaks inside one dawn are still one morning (owner's decision,
     * #1166), and a session that merely runs THROUGH the window was not started
     * in it. A session is dated by its own start in the listener's zone, exactly
     * like the night and holiday awards above.
     */
    private fun morningDays(startedAt: List<Long>): Long = startedAt
        .map { java.time.Instant.ofEpochMilli(it).atZone(zoneId) }
        .filter { it.hour in 6..7 }
        .map { it.toLocalDate() }
        .distinct()
        .size
        .toLong()

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
    private val aggregates = combine(withAbandoned, topology) { counters, topology ->
        counters.copy(downloadedBooks = topology.first, registeredSourceIds = registeredSourceIds,
            knownSeriesMemberships = topology.second)
    }
    private val local = LocalAchievementProgressSource(aggregates, store.observeFacts())
    override fun observe() = local.observe().distinctUntilChanged()
}
