package com.slukhayka.audiobooks.data.achievements

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.*
import com.slukhayka.audiobooks.testing.TestDataFactory
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class RoomAchievementProgressSourceTest {
    @Test fun `actual partial Room data never fabricates intent completion series or full downloads`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context,AudiobookDatabase::class.java).allowMainThreadQueries().build()
        val file = File.createTempFile("699-download-proof", ".mp3",context.cacheDir).apply { writeBytes(byteArrayOf(1)) }
        try {
            val store = RoomAchievementStore(database.achievementDao())
            val source = RoomAchievementProgressSource(database.achievementDao(),store,setOf("sluhay","youtube"))
            val empty = source.observe().first()
            assertEquals(0L,empty.explicitBooks); assertEquals(0L,empty.downloadedBooks)
            assertEquals(setOf("sluhay","youtube"),empty.registeredSourceIds)
            assertEquals(emptySet<AchievementSeriesMembership>(),empty.knownSeriesMemberships)
            val dao=database.audiobookDao()
            val book=TestDataFactory.dataBooks().first().copy(totalChapters=2)
            val chapters=TestDataFactory.dataChapters().filter { it.bookId==book.id }.take(2)
            dao.insertAudiobooks(listOf(book)); dao.insertChapters(chapters)
            dao.upsertLibraryEntry(book.id,book.id,false,123L,0f)
            dao.savePlaybackProgress(PlaybackProgressEntity("edition",book.id,0,0,123L,true))
            dao.upsertSeriesMember(SeriesMemberEntity(book.id,"partial-series",2))
            val sources=listOf("a","b").map { SourceEntity(it,book.id,type="sluhay",url="https://sluhay.com/$it") }
            dao.insertSources(sources)
            dao.insertTracks(listOf(SourceTrackEntity("a0","a",0,"url",file.absolutePath,isDownloaded=true),
                SourceTrackEntity("b1","b",1,"url",file.absolutePath,isDownloaded=true)))
            val partial=source.observe().first()
            assertEquals(0L,partial.explicitBooks); assertEquals(0L,partial.completedBooks); assertEquals(0L,partial.downloadedBooks)
            assertEquals(setOf(AchievementSeriesMembership("partial-series",book.id,2)),partial.knownSeriesMemberships)
            assertEquals(emptyList<AchievementDefinition>(),AchievementEvaluator.evaluate(partial,emptySet()))
            dao.updateLibraryEntryOrigin(book.id,"EXPLICIT_SAVE")
            dao.insertTracks(listOf(SourceTrackEntity("a1","a",1,"url",file.absolutePath,isDownloaded=true)))
            store.recordFact(AchievementFact.PLAYBACK_STARTED)
            val actual=source.observe().first()
            assertEquals(1L,actual.explicitBooks); assertEquals(1L,actual.downloadedBooks); assertEquals(1L,actual.playbackStarts)
            dao.updateBookStats(book.id,3,0L)
            assertEquals("missing known logical chapter is not a complete download",0L,source.observe().first().downloadedBooks)
            file.delete()
            assertEquals(0L,source.observe().first().downloadedBooks)
        } finally { database.close(); file.delete() }
    }

    /**
     * #700 (T2) — the speed bands must count STORED preferences and nothing
     * else.
     *
     * A NULL `preferredSpeed` means "use the global default", which is not a
     * claim that the book was heard at 1x — so it belongs to neither band. This
     * runs the REAL queries against the REAL schema, because the honesty lives
     * in the SQL (`WHERE preferredSpeed > 1.5`), not in Kotlin.
     */
    @Test fun `speed bands count stored preferences and ignore the null default`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val dao = database.audiobookDao()
            val achievementDao = database.achievementDao()

            // Three books: one explicitly fast, one explicitly slow, one with
            // NO stored preference (the global-default case).
            // The factory ships three books; the boundary case needs a fourth,
            // built here from one of them so every non-id column stays valid.
            val books = TestDataFactory.dataBooks().let { base ->
                base + listOf(base.first().copy(id = "boundary-book", title = "Межова"))
            }
            dao.insertAudiobooks(books)
            dao.savePlaybackProgress(PlaybackProgressEntity("e0", books[0].id, 0, 0, 1L, false, preferredSpeed = 2.0f))
            dao.savePlaybackProgress(PlaybackProgressEntity("e1", books[1].id, 0, 0, 1L, false, preferredSpeed = 0.5f))
            dao.savePlaybackProgress(PlaybackProgressEntity("e2", books[2].id, 0, 0, 1L, false, preferredSpeed = null))
            // A book BETWEEN the two bands: faster than the slow bound but NOT
            // above 1.5x. Without it a wrong fast bound (say 0.5) would still
            // count one book and the test would pass — which is exactly what my
            // first version of this test did.
            dao.savePlaybackProgress(PlaybackProgressEntity("e3", books[3].id, 0, 0, 1L, false, preferredSpeed = 1.2f))

            assertEquals("швидких мусить бути рівно одна — 1.2x не швидкий", 1L, achievementDao.observeFastBooks().first())
            assertEquals("повільних мусить бути рівно одна — 1.2x не повільний", 1L, achievementDao.observeSlowBooks().first())

            // And the ladder does not open on a single book — the thresholds are
            // the spec's, not mine.
            val snapshot = RoomAchievementProgressSource(
                achievementDao, RoomAchievementStore(achievementDao), emptySet()
            ).observe().first()
            assertEquals(1L, snapshot.fastBooks)
            assertTrue(
                "10 книг — поріг «Швидкісного», одна його не відкриває",
                AchievementEvaluator.evaluate(snapshot, emptySet()).map { it.id }.none {
                    it == "speedster_10"
                }
            )
        } finally {
            database.close()
        }
    }

    /**
     * #700 (T2) — a blank note is a BOOKMARK, not a note.
     *
     * `note` is non-null but may be blank, so counting rows would inflate the
     * note award with bookmarks the listener never wrote anything on. The test
     * puts a real note, an EMPTY one and a WHITESPACE-only one side by side —
     * the last two must not count, and the whitespace case is what proves the
     * query trims rather than comparing to "".
     */
    @Test fun `only a written note counts as a note`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val dao = database.audiobookDao()
            val achievementDao = database.achievementDao()
            val book = TestDataFactory.dataBooks().first()
            dao.insertAudiobooks(listOf(book))

            dao.insertBookmark(BookmarkEntity(bookId = book.id, chapterIndex = 0,
                chapterTitle = "Розділ", timestampSeconds = 1L, note = "справжня нотатка"))
            dao.insertBookmark(BookmarkEntity(bookId = book.id, chapterIndex = 1,
                chapterTitle = "Розділ", timestampSeconds = 2L, note = ""))
            dao.insertBookmark(BookmarkEntity(bookId = book.id, chapterIndex = 2,
                chapterTitle = "Розділ", timestampSeconds = 3L, note = "   "))

            assertEquals("закладок мусить бути три", 3L, achievementDao.observeBookmarks().first())
            assertEquals("нотатка мусить бути рівно одна", 1L, achievementDao.observeNotes().first())
        } finally {
            database.close()
        }
    }

    /**
     * #700 (T2) — relistens count BOOKS, not rows.
     *
     * Three RELISTEN rows for the SAME book must count as ONE: a listener who
     * replays one chapter three times returned to one book, and a row count
     * would let a single chapter satisfy the whole ladder. A second book makes
     * the difference visible — a broken DISTINCT would report 4, not 2.
     */
    @Test fun `relistens count distinct books, and timer stops count events`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val dao = database.audiobookDao()
            val achievementDao = database.achievementDao()
            val books = TestDataFactory.dataBooks().take(2)
            dao.insertAudiobooks(books)

            repeat(3) { index ->
                dao.insertPlaybackEvent(PlaybackEventEntity(
                    bookId = books[0].id, kind = PlaybackEventKind.RELISTEN, chapterIndex = index
                ))
            }
            dao.insertPlaybackEvent(PlaybackEventEntity(
                bookId = books[1].id, kind = PlaybackEventKind.RELISTEN, chapterIndex = 0
            ))
            repeat(4) {
                dao.insertPlaybackEvent(PlaybackEventEntity(
                    bookId = books[0].id, kind = PlaybackEventKind.TIMER_STOP, chapterIndex = 0
                ))
            }

            assertEquals(
                "чотири рядки RELISTEN — це ДВІ книги, а не чотири",
                2L, achievementDao.observeRelistens().first()
            )
            assertEquals("таймер мусить порахувати всі чотири зупинки", 4L, achievementDao.observeTimerStops().first())
        } finally {
            database.close()
        }
    }

    /**
     * #701 (T3) — doors count DISTINCT source types, not rows.
     *
     * Two books from the same source are ONE door: a listener who imported
     * four books from sluhayua went through one door, not four. The fixture
     * deliberately puts two rows on the same type and only one on each of the
     * others, so a row-counting bug reports 4 instead of 3 and fails here.
     */
    @Test fun `source doors count distinct types, not library rows`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val dao = database.audiobookDao()
            val achievementDao = database.achievementDao()
            val books = TestDataFactory.dataBooks()
            dao.insertAudiobooks(books)
            dao.insertSources(listOf(
                SourceEntity(id = "s1", bookId = books[0].id, type = "sluhayua", url = "https://a/1"),
                SourceEntity(id = "s2", bookId = books[0].id, type = "sluhayua", url = "https://a/2"),
                SourceEntity(id = "s3", bookId = books[1].id, type = "youtube", url = "https://b/1"),
                SourceEntity(id = "s4", bookId = books[2].id, type = "librivox", url = "https://c/1")
            ))

            assertEquals(
                "два рядки одного типу — це ОДНІ двері, тож усього три",
                3L, achievementDao.observeUsedSourceDoors().first()
            )
            assertFalse(
                "«Чотири двері» не мають відкриватись на трьох",
                AchievementEvaluator.evaluate(
                    RoomAchievementProgressSource(achievementDao, RoomAchievementStore(achievementDao), emptySet())
                        .observe().first(),
                    emptySet()
                ).map { it.id }.any { it == "four_doors" }
            )
        } finally {
            database.close()
        }
    }

    /**
     * #701 (T3) — languages count only REAL BCP-47 codes, and an UNKNOWN one is
     * not a language.
     *
     * The AC is explicit: «Мовні нагороди — з реального contentLanguage, не
     * вгаданого». An Edition whose language is empty must therefore add
     * nothing — otherwise two empty rows would open «Двомовний» on a guess. The
     * third row is the boundary: it has a real code and must count.
     */
    @Test fun `unknown language never counts as one, real codes do`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val achievementDao = database.achievementDao()
            val dao = database.audiobookDao()
            dao.insertEdition(EditionEntity(id = "ed-uk", workId = "w1", language = "uk"))
            dao.insertEdition(EditionEntity(id = "ed-empty", workId = "w2", language = ""))
            dao.insertEdition(EditionEntity(id = "ed-empty2", workId = "w3", language = ""))

            assertEquals(
                "дві порожні мови не рахуються — лишається один справжній код",
                1L, achievementDao.observeKnownLanguages().first()
            )
        } finally {
            database.close()
        }
    }

    /**
     * #701 (T3) — «Гість» is about the BROWSER door specifically.
     *
     * The WebView submission path is the only one that writes
     * `type = "youtube"` (`LibraryImport.importSubmittedYouTube`). A book
     * imported from a registry source must NOT earn it — otherwise the award
     * would just mean "you own something", which is what the first-steps
     * awards already say.
     */
    @Test fun `browser guest needs the browser door, not just any source`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val dao = database.audiobookDao()
            val achievementDao = database.achievementDao()
            val books = TestDataFactory.dataBooks()
            dao.insertAudiobooks(books)
            dao.insertSources(listOf(
                SourceEntity(id = "reg-1", bookId = books[0].id, type = "sluhay", url = "https://a/1"),
                SourceEntity(id = "reg-2", bookId = books[1].id, type = "librivox", url = "https://b/1")
            ))

            assertEquals(
                "без браузерного шляху «Гість» не має відкриватись",
                0L, achievementDao.observeBrowserBooks().first()
            )

            dao.insertSources(listOf(
                SourceEntity(id = "web-1", bookId = books[2].id, type = "youtube", url = "https://c/1")
            ))
            assertEquals("після браузерного імпорту — 1", 1L, achievementDao.observeBrowserBooks().first())
        } finally {
            database.close()
        }
    }

    /**
     * #702 (T4) — the genre count obeys BOTH rules the ticket sets.
     *
     * **Aggregated at Work level:** `work_genres` is keyed by
     * (workId, genreId, sourceId), so one Work whose genre is claimed by two
     * sources has TWO rows. Counting rows would say two books; counting
     * DISTINCT workId says one — which is the truth.
     *
     * **Only a real claim counts:** a Work with no `work_genres` row at all is
     * absent from the result, so it can neither widen nor deepen taste.
     */
    @Test fun `genre counts aggregate at Work level and need a real claim`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val dao = database.audiobookDao()
            val achievementDao = database.achievementDao()
            val books = TestDataFactory.dataBooks()
            dao.insertAudiobooks(books)
            for (book in books) dao.upsertLibraryEntry(book.id, book.id, false, 1L, 0f)

            dao.insertWorkGenres(listOf(
                // One Work, same genre, TWO sources — must count ONCE.
                WorkGenreEntity(books[0].id, "detective", "src-a"),
                WorkGenreEntity(books[0].id, "detective", "src-b"),
                // A second Work in a different genre.
                WorkGenreEntity(books[1].id, "fantasy", "src-a")
                // books[2] deliberately has NO genre row.
            ))

            val counts = achievementDao.observeGenreBookCounts().first()
                .associate { it.genreId to it.works }

            assertEquals("два джерела одного твору — це ОДНА книга", 1L, counts["detective"])
            assertEquals("друга книга — друга пара", 1L, counts["fantasy"])
            assertEquals("жанрів мусить бути рівно два", 2, counts.size)

            // And the snapshot carries it through to the awards.
            val snapshot = RoomAchievementProgressSource(
                achievementDao, RoomAchievementStore(achievementDao), emptySet()
            ).observe().first()
            assertEquals(2, snapshot.genreCounts.size)
            assertFalse(
                "двох жанрів замало для «Жанрового поліглота»",
                AchievementEvaluator.evaluate(snapshot, emptySet()).map { it.id }
                    .any { it == "genre_polyglot_8" }
            )
        } finally {
            database.close()
        }
    }

    /**
     * #704 (T6) — the series count is DISTINCT series, and a book with no
     * series is not a series.
     *
     * Two Works in the same series must count once; a Work with a null or blank
     * `seriesTitle` must not become a nameless one. A row-counting bug would
     * report 4 here instead of 2.
     */
    @Test fun `series count is distinct and ignores books with no series`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val dao = database.audiobookDao()
            val achievementDao = database.achievementDao()
            val books = TestDataFactory.dataBooks()
            dao.insertAudiobooks(books)
            for (book in books) dao.upsertLibraryEntry(book.id, book.id, false, 1L, 0f)

            dao.upsertWork(WorkEntity(id = books[0].id, mergeKey = "k0", title = "Т1",
                author = "А", seriesTitle = "Відьмак"))
            dao.upsertWork(WorkEntity(id = books[1].id, mergeKey = "k1", title = "Т2",
                author = "А", seriesTitle = "Відьмак"))
            // Same series again — must NOT add a third.
            dao.upsertWork(WorkEntity(id = books[2].id, mergeKey = "k2", title = "Т3",
                author = "А", seriesTitle = "Відьмак"))

            assertEquals("три книги однієї серії — це ОДНА серія", 1L,
                achievementDao.observeSeriesInLibrary().first())

            dao.upsertWork(WorkEntity(id = "no-series", mergeKey = "k3", title = "Без серії",
                author = "Б", seriesTitle = null))
            dao.upsertWork(WorkEntity(id = "blank-series", mergeKey = "k4", title = "Порожня",
                author = "Б", seriesTitle = ""))
            dao.upsertLibraryEntry("no-series", "no-series", false, 1L, 0f)
            dao.upsertLibraryEntry("blank-series", "blank-series", false, 1L, 0f)

            assertEquals("книга без серії не має ставати безіменною серією", 1L,
                achievementDao.observeSeriesInLibrary().first())
        } finally {
            database.close()
        }
    }

    /**
     * #703 (T5) — the hidden time-based awards, read from REAL event times.
     *
     * The zone is pinned so the assertions mean something: "between 02:00 and
     * 04:00" is not an instant, it is a local hour, and without a fixed zone the
     * test would pass or fail depending on the machine's clock settings.
     */
    @Test fun `night and holiday awards come from real event times`() = runBlocking {
        val kyiv = java.time.ZoneId.of("Europe/Kyiv")
        fun at(year: Int, month: Int, day: Int, hour: Int): Long =
            java.time.LocalDateTime.of(year, month, day, hour, 0)
                .atZone(kyiv).toInstant().toEpochMilli()

        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val dao = database.audiobookDao()
            val book = TestDataFactory.dataBooks().first()
            dao.insertAudiobooks(listOf(book))

            // 03:00 on New Year's Day: BOTH the night award and the holiday one.
            dao.insertPlaybackEvent(PlaybackEventEntity(bookId = book.id,
                kind = PlaybackEventKind.COMPLETED, timestamp = at(2026, 1, 1, 3)))
            // Midday in March: neither.
            dao.insertPlaybackEvent(PlaybackEventEntity(bookId = book.id,
                kind = PlaybackEventKind.COMPLETED, timestamp = at(2026, 3, 10, 12)))

            val source = RoomAchievementProgressSource(
                database.achievementDao(), RoomAchievementStore(database.achievementDao()),
                emptySet(), zoneId = kyiv
            )
            val snapshot = source.observe().first()

            assertEquals("нічних завершень мусить бути одне", 1L, snapshot.nightCompletions)
            assertEquals("святкових завершень мусить бути одне", 1L, snapshot.holidayCompletions)
            assertEquals("сесій не було — баланс нульовий", 0L, snapshot.owlLarkBalance)

            val earned = AchievementEvaluator.evaluate(snapshot, emptySet()).map { it.id }
            assertTrue("«Нічний вартовий» мусить відкритись", "night_watch" in earned)
            assertTrue("«Свято» мусить відкритись", "holiday" in earned)
            assertFalse("«Сова й жайворонок» не має відкриватись без сесій", "owl_and_lark" in earned)

            // Both ends of the day are needed, and one alone must not do it.
            dao.insertPlaybackEvent(PlaybackEventEntity(bookId = book.id,
                kind = PlaybackEventKind.RESUME, timestamp = at(2026, 3, 11, 5)))
            assertEquals("лише рання сесія — ще не баланс", 0L,
                source.observe().first().owlLarkBalance)

            dao.insertPlaybackEvent(PlaybackEventEntity(bookId = book.id,
                kind = PlaybackEventKind.RESUME, timestamp = at(2026, 3, 11, 23)))
            assertEquals("рання і пізня — баланс одиниця", 1L,
                source.observe().first().owlLarkBalance)
        } finally {
            database.close()
        }
    }

    /**
     * The zone is not decoration: the SAME instant is night in Kyiv and not in
     * London. If the computation ignored the injected zone, this would fail.
     */
    @Test fun `the same instant is night in one zone and not in another`() = runBlocking {
        val kyiv = java.time.ZoneId.of("Europe/Kyiv")
        val london = java.time.ZoneId.of("Europe/London")
        // 03:00 in Kyiv is 01:00 in London in winter.
        val instant = java.time.LocalDateTime.of(2026, 1, 15, 3, 0)
            .atZone(kyiv).toInstant().toEpochMilli()

        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val dao = database.audiobookDao()
            val book = TestDataFactory.dataBooks().first()
            dao.insertAudiobooks(listOf(book))
            dao.insertPlaybackEvent(PlaybackEventEntity(bookId = book.id,
                kind = PlaybackEventKind.COMPLETED, timestamp = instant))

            val inKyiv = RoomAchievementProgressSource(
                database.achievementDao(), RoomAchievementStore(database.achievementDao()),
                emptySet(), zoneId = kyiv
            ).observe().first()
            val inLondon = RoomAchievementProgressSource(
                database.achievementDao(), RoomAchievementStore(database.achievementDao()),
                emptySet(), zoneId = london
            ).observe().first()

            assertEquals("у Києві це ніч", 1L, inKyiv.nightCompletions)
            assertEquals("у Лондоні це ще не ніч", 0L, inLondon.nightCompletions)
        } finally {
            database.close()
        }
    }
}
