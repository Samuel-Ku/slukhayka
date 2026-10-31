package com.slukhayka.audiobooks.ui.screens

import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.EditionEntity
import com.slukhayka.audiobooks.data.db.LibraryEntryEntity
import com.slukhayka.audiobooks.data.db.PersonBookmarkEntity
import com.slukhayka.audiobooks.data.db.PersonRole
import com.slukhayka.audiobooks.data.db.PlaybackProgressEntity
import com.slukhayka.audiobooks.data.db.WorkEntity
import com.slukhayka.audiobooks.data.personbookmarks.PersonIdentity
import com.slukhayka.audiobooks.data.personbookmarks.PersonNewArrivals
import com.slukhayka.audiobooks.data.recommend.RecommendationEngine
import com.slukhayka.audiobooks.data.source.GlobalSearchResult
import com.slukhayka.audiobooks.data.source.GlobalSearchSource
import com.slukhayka.audiobooks.ui.durationBooksFrom
import com.slukhayka.audiobooks.ui.library.PersonalCycles
import com.slukhayka.audiobooks.ui.library.SimilarCycles
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1743 — the Огляд projections must run off the composition/main caller,
 * and must keep producing exactly what the old `remember { … }` produced.
 */
class HomeProjectionsTest {

    private val author = PersonIdentity.from(PersonRole.AUTHOR, "Ігор Петренко")
    private val narrator = PersonIdentity.from(PersonRole.NARRATOR, "Ігор Петренко")

    private val bookmarks = listOf(
        PersonBookmarkEntity(
            author.role.storageValue, author.id, author.displayName, author.normalizedName,
            lastSeenAt = 10
        ),
        PersonBookmarkEntity(
            narrator.role.storageValue, narrator.id, narrator.displayName, narrator.normalizedName,
            lastSeenAt = 10
        )
    )
    private val work = WorkEntity("work", "title|author", "Книга", "ігор петренко", addedAt = 11)
    private val edition = EditionEntity("edition", "4read-card", narrator = "Ігор Петренко", addedAt = 11)
    private val libraryEntry = LibraryEntryEntity("4read-card", work.id)
    private val card = GlobalSearchResult(
        title = "Книга",
        author = "Ігор Петренко",
        narrator = "Ігор Петренко",
        mergeKey = "title|author",
        sources = listOf(GlobalSearchSource("source-a", "A", "https://a.example/book"))
    )
    private val unifiedCatalog = listOf(
        card,
        card.copy(sources = listOf(GlobalSearchSource("source-b", "B", "https://b.example/book")))
    )

    private val shortBook = AudiobookEntity(
        id = "short",
        title = "Коротка",
        author = "Автор",
        narrator = "Диктор",
        description = "",
        coverDrawableRes = 0,
        genre = "Жанр",
        sourceUrl = "https://a.example/short",
        totalDurationSeconds = 3_600L
    )
    private val longBook = AudiobookEntity(
        id = "long",
        title = "Довга",
        author = "Автор",
        narrator = "Диктор",
        description = "",
        coverDrawableRes = 0,
        genre = "Жанр",
        sourceUrl = "https://a.example/long",
        totalDurationSeconds = 12 * 3_600L
    )
    private val ownSerialBook = AudiobookEntity(
        id = "own-serial",
        title = "Відьмак. Останнє бажання",
        author = "Анджей Сапковський",
        narrator = "Диктор",
        description = "",
        coverDrawableRes = 0,
        genre = "Фентезі",
        sourceUrl = "https://a.example/witcher"
    ).apply {
        seriesTitle = "Відьмак"
        seriesUrl = "https://a.example/series/witcher"
        workId = "witcher|sapkowski"
    }
    private val ownSerialWork = WorkEntity(
        id = "witcher|sapkowski",
        mergeKey = "witcher|sapkowski",
        title = "Відьмак. Останнє бажання",
        author = "анджей сапковський",
        seriesTitle = "Відьмак",
        seriesUrl = "https://a.example/series/witcher"
    )
    // An unmergeable Work (blank merge key): PersonalCycles' own tier indexes
    // by merge key, so only SimilarCycles can lift this one — both builders
    // still have to agree with the screen's old pipeline.
    private val similarWork = WorkEntity(
        id = "similar-work",
        mergeKey = "",
        title = "Меч призначення",
        author = "анджей сапковський",
        seriesTitle = "Саґа про Відьмака",
        seriesUrl = "https://a.example/series/saga"
    )
    private val recommendations = listOf(
        RecommendationEngine.Recommendation(
            candidate = RecommendationEngine.Candidate(
                id = similarWork.id,
                title = similarWork.title,
                author = similarWork.author
            ),
            score = 0.9,
            reasonTitle = "схоже на Відьмак"
        )
    )

    private val libraryBooks = listOf(shortBook, longBook, ownSerialBook)
    private val works = listOf(work, ownSerialWork, similarWork)

    @Test
    fun `projections are not invoked until awaited and run off the calling thread`() = runBlocking {
        val caller = Thread.currentThread()
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "home-projection-test")
        }
        val peopleCalls = AtomicInteger(0)
        val durationCalls = AtomicInteger(0)
        val cycleCalls = AtomicInteger(0)
        val seenThread = AtomicReference<Thread>()

        try {
            val projections = HomeProjections(
                dispatcher = executor.asCoroutineDispatcher(),
                projectPersonNewArrivals = { b, w, e, c, l ->
                    peopleCalls.incrementAndGet()
                    seenThread.set(Thread.currentThread())
                    PersonNewArrivals.projectCatalog(b, w, e, c, l)
                },
                projectDurationBooks = { books ->
                    durationCalls.incrementAndGet()
                    durationBooksFrom(books)
                },
                projectPersonalCycles = { books, progress, known, picks ->
                    cycleCalls.incrementAndGet()
                    PersonalCycles.build(books, progress, known, picks)
                },
                projectSimilarCycles = { picks, known, ownTitles ->
                    SimilarCycles.build(picks, known, ownTitles)
                }
            )

            // Constructing the projection holder is the composition-time step:
            // it must do no work at all.
            assertEquals(0, peopleCalls.get())
            assertEquals(0, durationCalls.get())
            assertEquals(0, cycleCalls.get())

            projections.peopleNewArrivals(
                bookmarks = bookmarks,
                works = works,
                editions = listOf(edition),
                unifiedCatalog = unifiedCatalog,
                libraryEntries = listOf(libraryEntry)
            )
            projections.durationBooks(libraryBooks)
            projections.cycleShelves(
                libraryBooks = libraryBooks,
                progress = emptyList<PlaybackProgressEntity>(),
                works = works,
                recommendations = recommendations
            )

            assertEquals(1, peopleCalls.get())
            assertEquals(1, durationCalls.get())
            assertEquals(1, cycleCalls.get())
            // Identity, not name: with coroutine debug enabled the runtime
            // decorates the worker name with " @coroutine#N".
            assertNotSame(caller, seenThread.get())
            assertTrue(seenThread.get().name.startsWith("home-projection-test"))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `projected output is identical to the current builders`() = runBlocking {
        val projections = HomeProjections()

        assertEquals(
            PersonNewArrivals.projectCatalog(
                bookmarks = bookmarks,
                works = works,
                editions = listOf(edition),
                unifiedCatalog = unifiedCatalog,
                libraryEntries = listOf(libraryEntry)
            ),
            projections.peopleNewArrivals(
                bookmarks = bookmarks,
                works = works,
                editions = listOf(edition),
                unifiedCatalog = unifiedCatalog,
                libraryEntries = listOf(libraryEntry)
            )
        )

        assertEquals(durationBooksFrom(libraryBooks), projections.durationBooks(libraryBooks))

        val expectedPersonal = PersonalCycles.build(
            libraryBooks = libraryBooks,
            progress = emptyList(),
            works = works,
            recommendations = recommendations
        )
        assertEquals(
            HomeCycleShelves(
                personal = expectedPersonal,
                similar = SimilarCycles.build(
                    picks = recommendations,
                    works = works,
                    ownCycleTitles = expectedPersonal.map { it.title }
                )
            ),
            projections.cycleShelves(
                libraryBooks = libraryBooks,
                progress = emptyList(),
                works = works,
                recommendations = recommendations
            )
        )
    }
}
