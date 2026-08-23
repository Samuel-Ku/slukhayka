package com.slukhayka.audiobooks.ui.library

import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.PlaybackProgressEntity
import com.slukhayka.audiobooks.data.db.WorkEntity
import com.slukhayka.audiobooks.data.recommend.RecommendationEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the spec-39 «Ваші цикли» shelf builder: grouping by the
 * normalized series title, unfinished-first ranking with a recency order,
 * the 15-card cap, the deterministic canonical 4read URL, and honest counts —
 * a progress line only from real numbers. The spec-39 T2 tier pins the lift
 * of RecommendationEngine picks to cycle level: own cycles first, similar
 * after with a reason, never a suggestion of something already owned.
 * No Robolectric, no Room, no Compose: the builder's input is plain entities
 * (the same shaped rows the screens already read, ADR-0008/ADR-0009).
 */
class PersonalCyclesTest {

    // --- fixtures -----------------------------------------------------------

    private fun book(
        id: String,
        seriesTitle: String?,
        seriesUrl: String? = null,
        coverImageUrl: String? = null,
        createdAt: Long = 0L,
        workId: String = "w-$id"
    ): AudiobookEntity = AudiobookEntity(
        id = id,
        title = "Книга $id",
        author = "Автор",
        narrator = "",
        description = "",
        coverDrawableRes = 0,
        coverImageUrl = coverImageUrl,
        genre = "",
        sourceUrl = "https://4read.org/$id"
    ).also {
        it.seriesTitle = seriesTitle
        it.seriesUrl = seriesUrl
        it.createdAt = createdAt
        it.workId = workId
    }

    private fun progress(
        bookId: String,
        completed: Boolean = false,
        lastListenedAt: Long = 0L
    ) = PlaybackProgressEntity(
        editionId = "e-$bookId",
        bookId = bookId,
        isCompleted = completed,
        lastListenedAt = lastListenedAt
    )

    private fun work(
        id: String,
        seriesTitle: String?,
        seriesUrl: String? = null,
        mergeKey: String = "",
        coverImageUrl: String? = null
    ) = WorkEntity(
        id = id,
        mergeKey = mergeKey,
        title = "Праця $id",
        author = "Автор",
        seriesTitle = seriesTitle,
        seriesUrl = seriesUrl,
        coverImageUrl = coverImageUrl
    )

    /** One engine pick over a catalogue card (the id is the Work merge key). */
    private fun rec(candidateMergeKey: String, reason: String, score: Double = 0.5) =
        RecommendationEngine.Recommendation(
            candidate = RecommendationEngine.Candidate(
                id = candidateMergeKey,
                title = "Кандидат",
                author = "Автор"
            ),
            score = score,
            reasonTitle = reason
        )

    // --- grouping -----------------------------------------------------------

    @Test
    fun `same named cycles with different url spellings collapse into one card`() {
        val shelf = PersonalCycles.build(
            libraryBooks = listOf(
                book("b1", seriesTitle = "Відьмак", seriesUrl = "https://4read.org/xfsearch/cikl/vidmak/"),
                book("b2", seriesTitle = "Відьмак (цикл)", seriesUrl = "https://4read.org/xfsearch/cikl/vidmak/"),
                // A different cycle entirely.
                book("b3", seriesTitle = "Гіперіон", seriesUrl = "https://4read.org/xfsearch/cikl/giperion/")
            ),
            progress = emptyList(),
            works = emptyList()
        )
        assertEquals(listOf("Відьмак", "Гіперіон").sorted(), shelf.map { it.title }.sorted())
    }

    @Test
    fun `display title is the most frequent raw spelling`() {
        val shelf = PersonalCycles.build(
            libraryBooks = listOf(
                book("b1", seriesTitle = "Відьмак", seriesUrl = "https://4read.org/v/"),
                book("b2", seriesTitle = "Відьмак (цикл)", seriesUrl = "https://4read.org/v/"),
                book("b3", seriesTitle = "Відьмак", seriesUrl = "https://4read.org/v/")
            ),
            progress = emptyList(),
            works = emptyList()
        )
        assertEquals(1, shelf.size)
        assertEquals("Відьмак", shelf.single().title)
    }

    @Test
    fun `books without a series never form a cycle`() {
        val shelf = PersonalCycles.build(
            libraryBooks = listOf(
                book("b1", seriesTitle = null),
                book("b2", seriesTitle = ""),
                book("b3", seriesTitle = "   ")
            ),
            progress = emptyList(),
            works = emptyList()
        )
        assertTrue(shelf.isEmpty())
    }

    // --- canonical URL ------------------------------------------------------

    @Test
    fun `canonical url is the most frequent 4read url among members`() {
        val common = "https://4read.org/xfsearch/cikl/vidmak/"
        val rare = "https://4read.org/xfsearch/cikl/vidmak-saga/"
        val shelf = PersonalCycles.build(
            libraryBooks = listOf(
                book("b1", seriesTitle = "Відьмак", seriesUrl = rare),
                book("b2", seriesTitle = "Відьмак", seriesUrl = common),
                book("b3", seriesTitle = "Відьмак", seriesUrl = common)
            ),
            progress = emptyList(),
            works = emptyList()
        )
        assertEquals(common, shelf.single().url)
    }

    @Test
    fun `frequency ties keep the earliest member url`() {
        val first = "https://4read.org/xfsearch/cikl/a/"
        val second = "https://4read.org/xfsearch/cikl/b/"
        val shelf = PersonalCycles.build(
            libraryBooks = listOf(
                book("b1", seriesTitle = "Цикл", seriesUrl = first),
                book("b2", seriesTitle = "Цикл", seriesUrl = second)
            ),
            progress = emptyList(),
            works = emptyList()
        )
        assertEquals(first, shelf.single().url)
    }

    @Test
    fun `a cycle whose members carry no 4read url is omitted entirely`() {
        val shelf = PersonalCycles.build(
            libraryBooks = listOf(
                book("b1", seriesTitle = "Слушай-цикл", seriesUrl = "https://sluhay.com.ua/series/x/"),
                book("b2", seriesTitle = "Без адреси", seriesUrl = null)
            ),
            progress = emptyList(),
            works = emptyList()
        )
        assertTrue(shelf.isEmpty())
    }

    // --- ranking ------------------------------------------------------------

    @Test
    fun `unfinished cycles rank above finished ones`() {
        val shelf = PersonalCycles.build(
            libraryBooks = listOf(
                book("f1", seriesTitle = "Завершений", seriesUrl = "https://4read.org/s/done/", createdAt = 10L),
                book("u1", seriesTitle = "Незавершений", seriesUrl = "https://4read.org/s/wip/", createdAt = 5L)
            ),
            progress = listOf(progress("f1", completed = true)),
            works = emptyList()
        )
        assertEquals(listOf("Незавершений", "Завершений"), shelf.map { it.title })
    }

    @Test
    fun `inside a group recency of listening orders the cycles`() {
        val shelf = PersonalCycles.build(
            libraryBooks = listOf(
                book("old", seriesTitle = "Старий", seriesUrl = "https://4read.org/s/old/"),
                book("new", seriesTitle = "Новий", seriesUrl = "https://4read.org/s/new/")
            ),
            progress = listOf(
                progress("old", lastListenedAt = 100L),
                progress("new", lastListenedAt = 200L)
            ),
            works = emptyList()
        )
        assertEquals(listOf("Новий", "Старий"), shelf.map { it.title })
    }

    @Test
    fun `a member without progress falls back to its entry creation time for recency`() {
        val shelf = PersonalCycles.build(
            libraryBooks = listOf(
                book("listened", seriesTitle = "Слуханий", seriesUrl = "https://4read.org/s/l/", createdAt = 1L),
                book("fresh-add", seriesTitle = "Свіжододаний", seriesUrl = "https://4read.org/s/f/", createdAt = 500L)
            ),
            progress = listOf(progress("listened", lastListenedAt = 100L)),
            works = emptyList()
        )
        assertEquals(listOf("Свіжододаний", "Слуханий"), shelf.map { it.title })
    }

    @Test
    fun `a fully finished cycle still renders below unfinished ones`() {
        val shelf = PersonalCycles.build(
            libraryBooks = listOf(
                book("f1", seriesTitle = "Пройдений", seriesUrl = "https://4read.org/s/p/")
            ),
            progress = listOf(progress("f1", completed = true)),
            works = emptyList()
        )
        assertEquals(1, shelf.size)
        assertTrue(shelf.single().finished)
    }

    // --- cap ----------------------------------------------------------------

    @Test
    fun `the shelf caps at fifteen cards`() {
        val books = (1..20).map { i ->
            book("b$i", seriesTitle = "Цикл $i", seriesUrl = "https://4read.org/s$i/", createdAt = i.toLong())
        }
        val shelf = PersonalCycles.build(books, emptyList(), emptyList())
        assertEquals(PersonalCycles.SHELF_LIMIT, shelf.size)
        // The freshest additions survive the cut; the oldest fall off.
        assertEquals("Цикл 20", shelf.first().title)
        assertEquals("Цикл 6", shelf.last().title)
    }

    // --- honest counts ------------------------------------------------------

    @Test
    fun `progress counts real completions against all locally known works`() {
        val shelf = PersonalCycles.build(
            libraryBooks = listOf(
                book("done1", seriesTitle = "Відьмак", seriesUrl = "https://4read.org/v/"),
                book("done2", seriesTitle = "Відьмак", seriesUrl = "https://4read.org/v/"),
                book("wip", seriesTitle = "Відьмак", seriesUrl = "https://4read.org/v/")
            ),
            progress = listOf(
                progress("done1", completed = true),
                progress("done2", completed = true)
            ),
            works = listOf(
                work("w-done1", "Відьмак"),
                work("w-done2", "Відьмак"),
                work("w-wip", "Відьмак"),
                // Two more volumes the catalogue knows but the user doesn't own.
                work("w-cat1", "Відьмак (цикл)"),
                work("w-cat2", "Відьмак")
            )
        )
        val cycle = shelf.single()
        assertEquals(2, cycle.listenedCount)
        assertEquals(5, cycle.totalCount)
    }

    @Test
    fun `counts stay truthful when the catalogue knows nothing yet`() {
        val shelf = PersonalCycles.build(
            libraryBooks = listOf(book("b1", "Самотній цикл", "https://4read.org/x/")),
            progress = emptyList(),
            works = emptyList()
        )
        val cycle = shelf.single()
        assertEquals(0, cycle.listenedCount)
        // The one owned volume is itself a locally known Work — Y is never
        // smaller than what the shelf can see.
        assertEquals(1, cycle.totalCount)
    }

    @Test
    fun `works of other cycles never inflate the count`() {
        val shelf = PersonalCycles.build(
            libraryBooks = listOf(book("b1", "Цикл А", "https://4read.org/a/")),
            progress = emptyList(),
            works = listOf(work("w1", "Цикл Б"), work("w2", "Цикл В"))
        )
        assertEquals(1, shelf.single().totalCount)
    }

    // --- representative cover ----------------------------------------------

    @Test
    fun `cover comes from the unfinished member with the freshest activity`() {
        val shelf = PersonalCycles.build(
            libraryBooks = listOf(
                book("stale", seriesTitle = "Цикл", seriesUrl = "https://4read.org/c/", coverImageUrl = "stale.png"),
                book("fresh", seriesTitle = "Цикл", seriesUrl = "https://4read.org/c/", coverImageUrl = "fresh.png"),
                book("finished", seriesTitle = "Цикл", seriesUrl = "https://4read.org/c/", coverImageUrl = "done.png")
            ),
            progress = listOf(
                progress("stale", lastListenedAt = 10L),
                progress("fresh", lastListenedAt = 20L),
                progress("finished", completed = true, lastListenedAt = 99L)
            ),
            works = emptyList()
        )
        assertEquals("fresh.png", shelf.single().coverImageUrl)
    }

    @Test
    fun `an all-finished cycle covers with its first member`() {
        val shelf = PersonalCycles.build(
            libraryBooks = listOf(
                book("first", seriesTitle = "Цикл", seriesUrl = "https://4read.org/c/", coverImageUrl = "first.png"),
                book("second", seriesTitle = "Цикл", seriesUrl = "https://4read.org/c/", coverImageUrl = "second.png")
            ),
            progress = listOf(progress("first", completed = true), progress("second", completed = true)),
            works = emptyList()
        )
        assertEquals("first.png", shelf.single().coverImageUrl)
    }

    // --- cold start ---------------------------------------------------------

    @Test
    fun `empty library yields an empty shelf`() {
        assertTrue(PersonalCycles.build(emptyList(), emptyList(), emptyList()).isEmpty())
    }

    // --- spec-39 T2: the similar tier ---------------------------------------

    @Test
    fun `empty recommendations keep the shelf identical to own-only`() {
        val library = listOf(book("b1", "Відьмак", "https://4read.org/v/"))
        val withParam = PersonalCycles.build(library, emptyList(), emptyList(), emptyList())
        val without = PersonalCycles.build(library, emptyList(), emptyList())
        assertEquals(without, withParam)
    }

    @Test
    fun `a recommended work lifts into a similar cycle after the own ones`() {
        val shelf = PersonalCycles.build(
            libraryBooks = listOf(
                book("own", seriesTitle = "Відьмак", seriesUrl = "https://4read.org/v/")
            ),
            progress = emptyList(),
            works = listOf(
                work("w-own", "Відьмак"),
                // The recommended candidate's Work — a different cycle.
                work("w-rec", "Гіперіон (цикл)", seriesUrl = "https://4read.org/g/", mergeKey = "гіперіон|автор"),
                // A second volume of the same identity carries the plain
                // spelling — the most frequent one becomes the display title.
                work("w-rec2", "Гіперіон", seriesUrl = "https://4read.org/g/"),
                work("w-rec3", "Гіперіон")
            ),
            recommendations = listOf(rec("гіперіон|автор", reason = "Книга own"))
        )
        assertEquals(listOf("Відьмак", "Гіперіон"), shelf.map { it.title })
        assertEquals("Книга own", shelf.last().reasonTitle)
        assertNull(shelf.first().reasonTitle)
    }

    @Test
    fun `similar cycle counts every locally known work of its identity`() {
        val shelf = PersonalCycles.build(
            libraryBooks = emptyList(),
            progress = emptyList(),
            works = listOf(
                work("w1", "Гіперіон", seriesUrl = "https://4read.org/g/"),
                work("w2", "Гіперіон (цикл)", mergeKey = "к|а")
            ),
            recommendations = listOf(rec("к|а", reason = "Щось"))
        )
        val similar = shelf.single()
        assertEquals(0, similar.listenedCount)
        assertEquals(2, similar.totalCount)
    }

    @Test
    fun `a candidate without a work row or without a series contributes nothing`() {
        val shelf = PersonalCycles.build(
            libraryBooks = emptyList(),
            progress = emptyList(),
            works = listOf(
                // Known merge key but no cycle on the Work.
                work("w1", null, mergeKey = "без-циклу|а")
            ),
            recommendations = listOf(
                rec("невідомий|ключ", reason = "X"),
                rec("без-циклу|а", reason = "Y")
            )
        )
        assertTrue(shelf.isEmpty())
    }

    @Test
    fun `a similar cycle needs an openable 4read url somewhere in its group`() {
        val shelf = PersonalCycles.build(
            libraryBooks = emptyList(),
            progress = emptyList(),
            works = listOf(
                // The candidate's own Work is stream-only elsewhere; another
                // same-identity Work carries the 4read page — openable.
                work("w1", "Цикл", seriesUrl = "https://sluhay.com.ua/s/x/", mergeKey = "ц|а"),
                work("w2", "Цикл", seriesUrl = "https://4read.org/c/")
            ),
            recommendations = listOf(rec("ц|а", reason = "R"))
        )
        assertEquals(1, shelf.size)
        assertEquals("https://4read.org/c/", shelf.single().url)
    }

    @Test
    fun `an identity group with no 4read url anywhere never renders`() {
        val shelf = PersonalCycles.build(
            libraryBooks = emptyList(),
            progress = emptyList(),
            works = listOf(
                work("w1", "Тільки sluhay", seriesUrl = "https://sluhay.com.ua/s/y/", mergeKey = "т|а")
            ),
            recommendations = listOf(rec("т|а", reason = "R"))
        )
        assertTrue(shelf.isEmpty())
    }

    @Test
    fun `own cycles are never suggested as similar`() {
        val shelf = PersonalCycles.build(
            libraryBooks = listOf(
                book("own", seriesTitle = "Відьмак", seriesUrl = "https://4read.org/v/")
            ),
            progress = emptyList(),
            works = listOf(
                work("w-own", "Відьмак"),
                // A catalogue volume of the SAME owned cycle.
                work("w-other", "Відьмак (цикл)", seriesUrl = "https://4read.org/v/", mergeKey = "в|а")
            ),
            recommendations = listOf(rec("в|а", reason = "Схоже на Відьмак"))
        )
        assertEquals(1, shelf.size)
        assertNull(shelf.single().reasonTitle)
    }

    @Test
    fun `an omitted own cycle is still excluded from the similar tier`() {
        // The listener owns «Мертвий цикл» but it renders nowhere: no member
        // carries a 4read url. The engine may still propose a catalogue book
        // of that same named cycle — it must not appear as a suggestion.
        val shelf = PersonalCycles.build(
            libraryBooks = listOf(
                book("own", seriesTitle = "Мертвий цикл", seriesUrl = null)
            ),
            progress = emptyList(),
            works = listOf(
                work("w-catalog", "Мертвий цикл", seriesUrl = "https://4read.org/m/", mergeKey = "м|а")
            ),
            recommendations = listOf(rec("м|а", reason = "R"))
        )
        assertTrue(shelf.isEmpty())
    }

    @Test
    fun `duplicate lifts of one cycle collapse keeping the strongest reason`() {
        val shelf = PersonalCycles.build(
            libraryBooks = emptyList(),
            progress = emptyList(),
            works = listOf(
                work("w1", "Гіперіон", seriesUrl = "https://4read.org/g/", mergeKey = "г|а"),
                work("w2", "Гіперіон (цикл)", mergeKey = "г2|а")
            ),
            recommendations = listOf(
                rec("г|а", reason = "Перша причина", score = 0.9),
                rec("г2|а", reason = "Друга причина", score = 0.5)
            )
        )
        assertEquals(1, shelf.size)
        assertEquals("Перша причина", shelf.single().reasonTitle)
    }

    @Test
    fun `the cap covers both tiers with own cycles first`() {
        val library = (1..10).map { i ->
            book("b$i", seriesTitle = "Свій $i", seriesUrl = "https://4read.org/o$i/", createdAt = i.toLong())
        }
        val works = (11..20).map { i ->
            work("w$i", "Чужий $i", seriesUrl = "https://4read.org/t$i/", mergeKey = "ч$i|а")
        }
        val recommendations = (11..20).map { i -> rec("ч$i|а", reason = "Причина $i") }
        val shelf = PersonalCycles.build(library, emptyList(), works, recommendations)
        assertEquals(PersonalCycles.SHELF_LIMIT, shelf.size)
        assertEquals(10, shelf.count { it.reasonTitle == null })
        assertEquals(5, shelf.count { it.reasonTitle != null })
        // Own tier keeps its ranking; the similar tier appends after it.
        assertEquals("Свій 10", shelf.first().title)
        assertTrue(shelf.drop(10).all { it.reasonTitle != null })
    }
}
