package com.slukhayka.audiobooks.data.achievements

data class AchievementProgress(
    val explicitBooks: Long = 0,
    val playbackStarts: Long = 0,
    val acceptedReviews: Long = 0,
    val notInterestedChoices: Long = 0,
    val searchImports: Long = 0,
    val offlinePlaybackStarts: Long = 0,
    val downloadedBooks: Long = 0,
    val completedBooks: Long = 0,
    /** #700 — completed books SHORTER than three hours («Коротка форма»). */
    val shortCompletedBooks: Long = 0,
    /** #700 — completed books of 30+ hours. */
    val epicCompletedBooks: Long = 0,
    /** #700 — books the listener chose to hear FASTER than 1.5x. */
    val fastBooks: Long = 0,
    /** #700 — books the listener chose to hear SLOWER than 0.75x. */
    val slowBooks: Long = 0,
    /** #700 — bookmarks the listener placed. */
    val bookmarks: Long = 0,
    /** #700 — bookmarks that actually carry a written note (non-blank). */
    val notes: Long = 0,
    /** #700 — sleep-timer stops the listener actually reached. */
    val timerStops: Long = 0,
    /** #700 — books the listener came BACK to and heard again. */
    val relistens: Long = 0,
    /**
     * #701 (T3) — how many DIFFERENT source doors the listener actually went
     * through.
     *
     * Not the same as [registeredSourceIds]: that is the set the app OFFERS.
     * This counts sources a library row actually points at, i.e. doors the
     * listener used.
     */
    val usedSourceDoors: Long = 0,
    /**
     * #701 (T3) — how many distinct, KNOWN narration languages the listener
     * has renditions in. Unknown (empty) is never counted as one.
     */
    val knownLanguages: Long = 0,
    /**
     * #701 (T3) — books imported through the BROWSER door. 0 or 1 is enough:
     * the award says "you went the harder way", and twice is not more true.
     */
    val browserBooks: Long = 0,
    /**
     * #702 (T4) — library Works per normalized genre id.
     *
     * A MAP rather than a dozen counters: the genre vocabulary is open
     * (`GenreIdentity` hashes unknown genres), so hardcoding ids would break
     * the moment a source claims a genre nobody listed. The two awards that use
     * it ask about BREADTH and DEPTH, not about a named genre.
     */
    val genreCounts: Map<String, Long> = emptyMap(),
    /**
     * #704 (T6) — how many DISTINCT series the listener has books from.
     *
     * Read from `works.seriesTitle` on the library rows, a real bibliographic
     * property of the books they hold. Deliberately NOT `series_members`: that
     * table is written only for a Work someone OPENED, so it would undercount
     * every series they own but never tapped.
     */
    val seriesInLibrary: Long = 0,
    /** #703 (T5) — books finished between 02:00 and 04:00 local time. */
    val nightCompletions: Long = 0,
    /**
     * #703 (T5) — min(sessions started before 06:00, sessions started after
     * 22:00). One number rather than two, because the award needs BOTH, and the
     * evaluator's shape is one metric against one threshold.
     */
    val owlLarkBalance: Long = 0,
    /** #703 (T5) — books finished on New Year or Christmas. */
    val holidayCompletions: Long = 0,
    /** #703 (T5) — books finished a year or more after they were added. */
    val vintageCompletions: Long = 0,
    /** #703 (T5) — books returned to after a six-month break. */
    val returnsAfterBreak: Long = 0,
    /** #703 (T5) — books finished two years or more after they were opened. */
    val lateCompletions: Long = 0,
    /** #1166 (T8) — the listener's best single day of verified listening. */
    val bestDayMillis: Long = 0,
    /**
     * #1166 (T8) — longest run of ADJACENT days with listening.
     *
     * Adjacency is a property of the DATES, never of the row count: a day
     * without listening has no row, so counting rows would bridge any silence.
     */
    val longestDayStreak: Long = 0,
    /** #1166 (T8) — most days with listening inside one calendar month. */
    val bestMonthDays: Long = 0,
    /** #1166 (T8) — how many different Mondays carried listening. */
    val mondaysListened: Long = 0,
    /**
     * #700 (T2) — books the listener FINISHED A SECOND TIME («Друге дихання»).
     *
     * Two `COMPLETED` events on the same book, i.e. two separate listening
     * cycles: the player logs one completion per cycle, and a fresh load at the
     * very end of a finished book goes through the relisten rule (back to
     * chapter 0, `RELISTEN`) rather than logging a second completion
     * (`AudioPlayerManager.loadAndPlayBook`). The log's cap keeps the newest two
     * completions for the same reason (`PlaybackEventPolicy`).
     *
     * This is NARROWER than the ticket's «завершення після повернення»: a book
     * abandoned halfway and finished later is an ordinary first completion, and
     * nothing recorded tells the two apart. The `RELISTEN` pairing is not used
     * either — «Почати спочатку» on an unfinished book writes `RELISTEN` with no
     * completion before it.
     */
    val booksFinishedTwice: Long = 0,
    /**
     * #701 — «П'ять поспіль»: the longest run of consecutive completions (in
     * time) whose Works belong to ONE series, counted in DISTINCT books.
     *
     * A completion with no named series BREAKS the run — it happened between
     * volumes, so they are not «підряд» (ADR-0014) — and a relisten of one book
     * never adds a tome to it (see `SeriesRun`).
     */
    val longestSeriesRun: Long = 0,
    /**
     * #701 — «Англомовний старт»: books with a recorded start whose rendition
     * really claims English — a BCP-47 `en` after `LanguageCode.normalize`,
     * never a guessed language.
     */
    val englishStartBooks: Long = 0,
    val verifiedListeningMillis: Long = 0,
    /**
     * #1183 (T9b) — verified milliseconds really played from a local file or a
     * downloaded content URI (`listening_stats.offlineListenedMillis`).
     *
     * STRONG evidence only: the player confirmed the LOCAL source was playing,
     * never `book.isDownloaded` and never the mere absence of a network
     * (ADR-0014, ADR-0060). The column starts at zero in v54, so nothing heard
     * before the measurement layer existed can open an offline award.
     */
    val offlineMillis: Long = 0,
    /**
     * #1183 (T9b) — verified milliseconds really played on a Cast receiver
     * (`castListenedMillis`). The receiver's own confirmed PLAYING state, never
     * the `isCasting` flag and never a transport command (ADR-0060).
     */
    val castMillis: Long = 0,
    /**
     * #1183 (T9b) — verified milliseconds written inside the 00:00–04:00 local
     * window, summed over days (`nightListenedMillis`).
     *
     * The window itself and the whole-tick attribution belong to the writer
     * (`NightWindow`, `ListeningStateStore`); this snapshot only sums what was
     * recorded, so no session is split or stretched into the night here.
     */
    val nightMillis: Long = 0,
    /**
     * #1183 (T9b) — the longest single session, in verified milliseconds
     * (`playback_sessions.verifiedMillis`).
     *
     * One session is one continuous interval: a pause of fifteen minutes or
     * more, a stop, or another book closes it (`PlaybackSessionPolicy`).
     * Sessions are never pruned, so the longest one is monotone.
     */
    val longestSessionMillis: Long = 0,
    /**
     * #1183 (T9b) — the longest single session measured by the OFFLINE
     * milliseconds inside it («Літак»).
     *
     * Not the same metric as [offlineMillis]: ten offline hours spread over ten
     * short sessions are a download habit, not one long trip.
     */
    val longestOfflineSessionMillis: Long = 0,
    /**
     * #1183 (T9b) — how many times the «до кінця розділу» mode was ARMED
     * (`AchievementCounter.END_OF_CHAPTER_ARM`), not how many chapters ended.
     *
     * At a chapter boundary the timer re-arms for the next chapter and a
     * `TIMER_STOP` event may never be written, so the armed moment is the only
     * honest fact (#700, ADR-0060).
     */
    val endOfChapterArms: Long = 0,
    /**
     * #1183 (T9b) — how many DIFFERENT local mornings carried a session that
     * STARTED between 06:00 and 08:00.
     *
     * The unit is the morning — a calendar date — not the session: five breaks
     * inside one dawn are still one morning (owner's decision, #1166).
     */
    val morningDays: Long = 0,
    val registeredSourceIds: Set<String> = emptySet(),
    val knownSeriesMemberships: Set<AchievementSeriesMembership> = emptySet()
)
/** Membership asserted by an existing source/cache; it never implies a complete series. */
data class AchievementSeriesMembership(val seriesId: String, val workId: String, val position: Int)

enum class AchievementMetric {
    EXPLICIT_BOOKS, PLAYBACK_STARTS, ACCEPTED_REVIEWS, NOT_INTERESTED,
    SEARCH_IMPORTS, OFFLINE_PLAYBACK_STARTS, DOWNLOADED_BOOKS, COMPLETED_BOOKS, LISTENING_MILLIS,
    SHORT_COMPLETED_BOOKS, EPIC_COMPLETED_BOOKS, FAST_BOOKS, SLOW_BOOKS, BOOKMARKS, NOTES,
    TIMER_STOPS, RELISTENS, USED_SOURCE_DOORS, KNOWN_LANGUAGES, BROWSER_BOOKS,
    DISTINCT_GENRES, MAX_GENRE_BOOKS, GENRE_BOOKS, NIGHT_COMPLETIONS, OWL_LARK, HOLIDAY_COMPLETIONS,
    VINTAGE_COMPLETIONS, RETURNS_AFTER_BREAK, LATE_COMPLETIONS, BOOKS_FINISHED_TWICE,
    LONGEST_SERIES_RUN, ENGLISH_STARTS, BEST_DAY_MILLIS, LONGEST_DAY_STREAK,
    BEST_MONTH_DAYS, MONDAY_DAYS,
    // #1183 (T9b) — the awards built on the measurement layer (#1173): the
    // recorded day columns, the session table and the durable counter.
    OFFLINE_MILLIS, CAST_MILLIS, NIGHT_MILLIS, LONGEST_SESSION_MILLIS,
    LONGEST_OFFLINE_SESSION_MILLIS, END_OF_CHAPTER_ARMS, MORNING_DAYS;

    /**
     * [genreId] is read by [GENRE_BOOKS] alone — the one metric that asks about
     * a NAMED genre, and therefore the only one that needs something beside the
     * snapshot. Every other metric ignores it.
     */
    fun value(snapshot: AchievementProgress, genreId: String? = null): Long = when (this) {
        EXPLICIT_BOOKS -> snapshot.explicitBooks
        PLAYBACK_STARTS -> snapshot.playbackStarts
        ACCEPTED_REVIEWS -> snapshot.acceptedReviews
        NOT_INTERESTED -> snapshot.notInterestedChoices
        SEARCH_IMPORTS -> snapshot.searchImports
        OFFLINE_PLAYBACK_STARTS -> snapshot.offlinePlaybackStarts
        DOWNLOADED_BOOKS -> snapshot.downloadedBooks
        COMPLETED_BOOKS -> snapshot.completedBooks
        SHORT_COMPLETED_BOOKS -> snapshot.shortCompletedBooks
        EPIC_COMPLETED_BOOKS -> snapshot.epicCompletedBooks
        FAST_BOOKS -> snapshot.fastBooks
        SLOW_BOOKS -> snapshot.slowBooks
        BOOKMARKS -> snapshot.bookmarks
        NOTES -> snapshot.notes
        TIMER_STOPS -> snapshot.timerStops
        RELISTENS -> snapshot.relistens
        USED_SOURCE_DOORS -> snapshot.usedSourceDoors
        KNOWN_LANGUAGES -> snapshot.knownLanguages
        BROWSER_BOOKS -> snapshot.browserBooks
        DISTINCT_GENRES -> snapshot.genreCounts.size.toLong()
        MAX_GENRE_BOOKS -> snapshot.genreCounts.values.maxOrNull() ?: 0L
        // #702 (T4, зріз 2) — books of ONE NAMED genre. A genre the snapshot
        // never saw is zero, so an award can never open on a genre nobody
        // claimed (ADR-0014).
        GENRE_BOOKS -> genreId?.let { snapshot.genreCounts[it] } ?: 0L
        NIGHT_COMPLETIONS -> snapshot.nightCompletions
        OWL_LARK -> snapshot.owlLarkBalance
        HOLIDAY_COMPLETIONS -> snapshot.holidayCompletions
        VINTAGE_COMPLETIONS -> snapshot.vintageCompletions
        RETURNS_AFTER_BREAK -> snapshot.returnsAfterBreak
        LATE_COMPLETIONS -> snapshot.lateCompletions
        BEST_DAY_MILLIS -> snapshot.bestDayMillis
        LONGEST_DAY_STREAK -> snapshot.longestDayStreak
        BEST_MONTH_DAYS -> snapshot.bestMonthDays
        MONDAY_DAYS -> snapshot.mondaysListened
        BOOKS_FINISHED_TWICE -> snapshot.booksFinishedTwice
        LONGEST_SERIES_RUN -> snapshot.longestSeriesRun
        ENGLISH_STARTS -> snapshot.englishStartBooks
        LISTENING_MILLIS -> snapshot.verifiedListeningMillis
        OFFLINE_MILLIS -> snapshot.offlineMillis
        CAST_MILLIS -> snapshot.castMillis
        NIGHT_MILLIS -> snapshot.nightMillis
        LONGEST_SESSION_MILLIS -> snapshot.longestSessionMillis
        LONGEST_OFFLINE_SESSION_MILLIS -> snapshot.longestOfflineSessionMillis
        END_OF_CHAPTER_ARMS -> snapshot.endOfChapterArms
        MORNING_DAYS -> snapshot.morningDays
    }
}

data class AchievementDefinition(
    val id: String,
    val group: String,
    val level: Int,
    val metric: AchievementMetric,
    val threshold: Long,
    val hidden: Boolean = false,
    /**
     * #702 (T4, зріз 2) — the named genre this tier asks about, from
     * `GenreIdentity`. Null for every award that does not name one.
     */
    val genreId: String? = null
)

object AchievementCatalog {
    /**
     * #702 (T4, зріз 2) — «10 книг у жанрі» для кожної полиці, яку джерела
     * РЕАЛЬНО заявляють і словник знає.
     *
     * Кожне написання спостережене на збереженій сторінці джерела (4read,
     * sound-books, lihtar), а не виведене з назви чи опису книжки. Порядок —
     * як у тікеті.
     *
     * «Класика» і «нон-фікшн» із тікета тут ВІДСУТНІ НАВМИСНО: жодне джерело в
     * жодній зібраній фікстурі цих слів не заявляє, а вигадати жанр — саме те,
     * що забороняє ADR-0014. Тест пінить цю прогалину як рішення: щойно джерело
     * заявить текст, полиця додається сюди одним рядком.
     */
    private val namedGenreTiers = listOf(
        "detective",
        "fantasy",
        "science-fiction",
        "romance",
        "horror",
        "childrens-literature",
        "historical-prose",
        "adventure",
        "self-development",
        "biography"
    )

    val definitions: List<AchievementDefinition> = listOf(
        AchievementDefinition("first_book", "first_steps", 1, AchievementMetric.EXPLICIT_BOOKS, 1),
        AchievementDefinition("first_playback", "first_steps", 1, AchievementMetric.PLAYBACK_STARTS, 1),
        AchievementDefinition("first_review", "first_steps", 1, AchievementMetric.ACCEPTED_REVIEWS, 1),
        AchievementDefinition("first_not_interested", "first_steps", 1, AchievementMetric.NOT_INTERESTED, 1),
        AchievementDefinition("first_search_import", "first_steps", 1, AchievementMetric.SEARCH_IMPORTS, 1),
        AchievementDefinition("first_offline_playback", "first_steps", 1, AchievementMetric.OFFLINE_PLAYBACK_STARTS, 1),
        AchievementDefinition("first_download", "first_steps", 1, AchievementMetric.DOWNLOADED_BOOKS, 1),
        AchievementDefinition("first_completion", "first_steps", 1, AchievementMetric.COMPLETED_BOOKS, 1)
    ) + listOf(1L, 10L, 100L, 1000L, 5000L).mapIndexed { index, hours ->
        AchievementDefinition("hours_$hours", "hours", index + 1, AchievementMetric.LISTENING_MILLIS, hours * 3_600_000L)
    } + listOf(1L, 5L, 10L, 25L, 50L, 100L, 250L, 500L).mapIndexed { index, books ->
        // #700 (T2) — the book path. Thresholds are the spec's, verbatim; the
        // metric is the SAME real completed count T1 already reads
        // (`observeCompletedBooks`), so nothing here is inferred.
        AchievementDefinition("books_$books", "books", index + 1, AchievementMetric.COMPLETED_BOOKS, books)
    } + listOf(
        // #700 (T2) — shape of the reading, not just its size. Both bands come
        // from a real join: completed `playback_events` against the book's own
        // `totalDurationSeconds`. A book whose duration is unknown (0) counts
        // in NEITHER band — a guess is not a fact (ADR-0014).
        AchievementDefinition("short_form_10", "books_shape", 1, AchievementMetric.SHORT_COMPLETED_BOOKS, 10),
        AchievementDefinition("epic_1", "books_shape", 1, AchievementMetric.EPIC_COMPLETED_BOOKS, 1),
        AchievementDefinition("long_liver_5", "books_shape", 2, AchievementMetric.EPIC_COMPLETED_BOOKS, 5),
        // #700 (T2) — HOW the listener listens. The speed is the stored
        // per-book preference (ADR-0009); a book with NO stored preference is
        // not counted at all, because "no preference" is the global default,
        // not a claim about this book (ADR-0014).
        AchievementDefinition("speedster_10", "habits", 1, AchievementMetric.FAST_BOOKS, 10),
        AchievementDefinition("slow_savour_5", "habits", 1, AchievementMetric.SLOW_BOOKS, 5),
        // #700 (T2) — marking your place, and writing something there. A
        // bookmark with a blank note is a bookmark, NOT a note: the two counts
        // stay apart so neither award can claim something the listener did not
        // do (ADR-0014).
        AchievementDefinition("bookmarks_50", "habits", 2, AchievementMetric.BOOKMARKS, 50),
        AchievementDefinition("notes_10", "habits", 3, AchievementMetric.NOTES, 10),
        // #700 (T2) — falling asleep to a book, and coming back to one. Both
        // are RECORDED events (`TIMER_STOP`, `RELISTEN`), not inferred from
        // anything else — the app already writes them.
        AchievementDefinition("sleep_timer_20", "habits", 4, AchievementMetric.TIMER_STOPS, 20),
        // #1183 (T9b) — «До кінця розділу»: ten times the mode was ARMED, the
        // moment the listener turned it on. `TIMER_STOP` would undercount, and
        // is a different fact (#700).
        AchievementDefinition("chapter_end_10", "habits", 5, AchievementMetric.END_OF_CHAPTER_ARMS, 10),
        AchievementDefinition("relisten_1", "relisten", 1, AchievementMetric.RELISTENS, 1),
        AchievementDefinition("relisten_5", "relisten", 2, AchievementMetric.RELISTENS, 5),
        // #700 (T2) — «Друге дихання»: the SAME book finished twice. The ticket
        // words it as «завершення після повернення»; the recorded proof is the
        // narrower fact — two `COMPLETED` cycles on one book, since the player's
        // `completionLogged` resets only when a cycle ends, not inside one. A
        // RELISTEN pairing would misfire: «Почати спочатку» writes RELISTEN on
        // an unfinished book.
        AchievementDefinition("second_wind", "relisten", 3, AchievementMetric.BOOKS_FINISHED_TWICE, 1),
        // #700 (T2) — «Глибокий запас»: ten books actually downloaded for
        // offline use. Built on the same real proof T1 already uses for
        // `first_download` (a track row that is downloaded AND whose file is
        // really on disk), so a row alone cannot claim it.
        AchievementDefinition("deep_reserve_10", "offline", 1, AchievementMetric.DOWNLOADED_BOOKS, 10),
        // #1183 (T9b) — the rest of the offline group. The three awards #700
        // left out because nothing recorded offline HOURS are here now: the
        // measurement layer from #1173 writes that column per day from the
        // STRONG evidence of a local source really playing. They read the
        // column, never the count of offline starts — a start is a different
        // fact and would open «Автономний» on the first minute (ADR-0014).
        AchievementDefinition("autonomous_10h", "offline", 2, AchievementMetric.OFFLINE_MILLIS, 10 * 3_600_000L),
        AchievementDefinition("download_gourmet_100h", "offline", 3, AchievementMetric.OFFLINE_MILLIS, 100 * 3_600_000L),
        // «Літак» is the LONGEST offline session, not the FIRST one: the first
        // offline session already has its own award (`first_offline_playback`),
        // and two notices for one fact are not two achievements (#700).
        AchievementDefinition("airplane_2h", "offline", 4, AchievementMetric.LONGEST_OFFLINE_SESSION_MILLIS, 2 * 3_600_000L),
        // #1183 (T9b) — «Великий екран»: hours on a receiver, which only its
        // own confirmed PLAYING state proves (ADR-0060).
        AchievementDefinition("big_screen_10h", "cast", 1, AchievementMetric.CAST_MILLIS, 10 * 3_600_000L),
        // #1183 (T9b) — the shape of a SESSION, from the recorded sessions and
        // day rows: one long sitting, the night hours actually played, and the
        // dawns that opened a session. Every metric is monotone, so no award
        // here can be taken back.
        AchievementDefinition("sync_4h", "sessions", 1, AchievementMetric.LONGEST_SESSION_MILLIS, 4 * 3_600_000L),
        AchievementDefinition("night_shift_2h", "sessions", 2, AchievementMetric.NIGHT_MILLIS, 2 * 3_600_000L),
        AchievementDefinition("dawn_5", "sessions", 3, AchievementMetric.MORNING_DAYS, 5),
        // #701 (T3) — «Чотири двері»: listening from four DIFFERENT sources.
        // Counts doors the listener actually used (library rows), never the
        // set the app merely offers — those are different facts.
        AchievementDefinition("four_doors", "doors", 1, AchievementMetric.USED_SOURCE_DOORS, 4),
        // #701 (T3) — languages, from real BCP-47 codes only (CONTEXT.md), never
        // guessed from text or a URL. An Edition whose language is unknown does
        // not count, so these cannot open on a guess.
        //
        // NOTE: the ticket names the awards but not their thresholds, and the
        // spec gives none either. These two are the smallest honest ladder —
        // a second language, then three — and they are pinned by tests so a
        // later change is a decision rather than drift.
        AchievementDefinition("bilingual_2", "languages", 1, AchievementMetric.KNOWN_LANGUAGES, 2),
        AchievementDefinition("polyglot_3", "languages", 2, AchievementMetric.KNOWN_LANGUAGES, 3),
        // #701 (T3) — «Гість»: a book imported through the BROWSER door. The
        // WebView submission path records `type = "youtube"`
        // (`LibraryImport.importSubmittedYouTube`), so this counts that path
        // rather than any book that merely lives on YouTube.
        AchievementDefinition("browser_guest", "doors", 2, AchievementMetric.BROWSER_BOOKS, 1),
        // #701 (T3) — «Глибокий пошук»: a book found through GLOBAL search. The
        // fact already exists and is written only when the import really came
        // from that path (`MainViewModel` records SEARCH_IMPORTED when
        // `target.fromGlobalSearch`), so this rewards the mechanism rather than
        // any import that happens to follow a search.
        AchievementDefinition("deep_search", "mechanisms", 1, AchievementMetric.SEARCH_IMPORTS, 1),
        // #701 — «П'ять поспіль» (spec story 35): five consecutive completions
        // that belong to Works of ONE series. Read LITERALLY as a run in time,
        // never as «томи за порядком номерів» — `works.seriesIndex` exists, but
        // it is INCOMPLETE and unproven (a source may leave it empty), so
        // ordering by it would be a guess (ADR-0014). A completion with no
        // series breaks the run, and a relisten of one book adds no tome; see
        // `SeriesRun`.
        AchievementDefinition("five_in_a_row", "series", 1, AchievementMetric.LONGEST_SERIES_RUN, 5),
        // #701 — «Англомовний старт»: a book the listener STARTED whose
        // rendition really claims English. The ticket names the award but sets
        // no threshold, and the spec does not mention it at all, so the
        // smallest honest one — the FIRST English start — is pinned by a test;
        // a later change must then be a decision rather than drift.
        AchievementDefinition("english_start", "languages", 3, AchievementMetric.ENGLISH_STARTS, 1),
        // #702 (T4) — BREADTH and DEPTH of taste. Both rest on real genre
        // claims only: a Work nobody claimed a genre for is absent from
        // `genreCounts`, so it can neither widen nor deepen anything.
        //
        // Breadth counts genres with at least one owned Work — «8 різних
        // жанрів» / «12 жанрів», read literally. Depth takes the largest single
        // genre. The NAMED «10 books in genre X» tiers are a separate block at
        // the end of this catalogue: they name a genre, so each one needs a
        // canonical id from `GenreIdentity` rather than a count of whatever the
        // sources happened to hash.
        AchievementDefinition("genre_polyglot_8", "genres", 1, AchievementMetric.DISTINCT_GENRES, 8),
        AchievementDefinition("omnivore_12", "genres", 2, AchievementMetric.DISTINCT_GENRES, 12),
        AchievementDefinition("mono_genre_25", "genres", 3, AchievementMetric.MAX_GENRE_BOOKS, 25),
        // #703 (T5) — HIDDEN awards. They are earned from the same real events
        // as everything else, but the screen must not name them beforehand, so
        // `hidden = true` is set and the notice still fires on the day.
        //
        // «Сова й жайворонок» needs BOTH an early and a late session, which is
        // why the snapshot carries their minimum rather than two counters.
        AchievementDefinition("night_watch", "hidden", 1, AchievementMetric.NIGHT_COMPLETIONS, 1, hidden = true),
        AchievementDefinition("owl_and_lark", "hidden", 1, AchievementMetric.OWL_LARK, 1, hidden = true),
        AchievementDefinition("holiday", "hidden", 1, AchievementMetric.HOLIDAY_COMPLETIONS, 1, hidden = true),
        // #703 (T5) — the rest of the hidden set. «Ювілей години» reuses the
        // hours metric rather than adding one: the hundredth hour IS a hundred
        // hours, and a second counter would be the same fact twice.
        AchievementDefinition("vintage", "hidden", 1, AchievementMetric.VINTAGE_COMPLETIONS, 1, hidden = true),
        AchievementDefinition("comeback", "hidden", 1, AchievementMetric.RETURNS_AFTER_BREAK, 1, hidden = true),
        AchievementDefinition("never_too_late", "hidden", 1, AchievementMetric.LATE_COMPLETIONS, 1, hidden = true),
        // #1166 (T8) — the SHAPE of listening across days. These are the spec's
        // user stories 10–12 and 17, which no earlier ticket picked up.
        //
        // The spec names the awards but not the numbers, so the numbers are
        // pinned by `ListeningRhythmTest` and `RegularityAwardsTest` — the same
        // "a later change has to be a decision, not drift" rule this catalogue
        // already follows. «День зі слуханням» is one verified minute
        // (`ListeningRhythm.DAY_MILLIS`) — the old five-second counter is not
        // proof, so pre-v51 rows cannot open these.
        //
        // Every metric here is MONOTONE (best day, longest streak, fullest
        // month, count of Mondays), so an award can never be taken back by a
        // quiet week or by a month rolling over.
        AchievementDefinition("marathon_3h", "regularity", 1, AchievementMetric.BEST_DAY_MILLIS, 3 * 3_600_000L),
        AchievementDefinition("week_in_earphones", "regularity", 2, AchievementMetric.LONGEST_DAY_STREAK, 7),
        AchievementDefinition("month_in_earphones", "regularity", 3, AchievementMetric.BEST_MONTH_DAYS, 20),
        AchievementDefinition("mondays_10", "regularity", 4, AchievementMetric.MONDAY_DAYS, 10)
        // NOTE: «Ювілей години» (the hundredth hour) is deliberately ABSENT. The
        // hour ladder from T1 already has `hours_100` on the SAME metric at the
        // SAME threshold, so adding it would fire two awards — and two notices —
        // for one event. A duplicate is not a second achievement.
    ) + namedGenreTiers.mapIndexed { index, genreId ->
        AchievementDefinition(
            id = "genre_${genreId.replace('-', '_')}_10",
            group = "genres",
            level = index + 4,
            metric = AchievementMetric.GENRE_BOOKS,
            threshold = 10,
            genreId = genreId
        )
    }
}

object AchievementEvaluator {
    fun evaluate(
        snapshot: AchievementProgress,
        alreadyEarned: Set<String>,
        catalog: List<AchievementDefinition> = AchievementCatalog.definitions
    ): List<AchievementDefinition> = catalog.filter {
        it.id !in alreadyEarned && it.metric.value(snapshot, it.genreId) >= it.threshold
    }
}
