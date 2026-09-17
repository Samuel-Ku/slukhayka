package com.slukhayka.audiobooks.data.search

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.WorkEntity
import com.slukhayka.audiobooks.data.db.WorkSourceEntity
import com.slukhayka.audiobooks.data.merge.MergeKey
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #826 — the acceptance measurement of the local search index: a ~10k-Work
 * mirror answers the quality set locally, with per-query timings printed
 * for the record and a generous anti-regression bound asserted.
 *
 * The bound is deliberately loose for Robolectric's JVM SQLite (a real
 * device is faster): it guards against pathological shape changes (full
 * scans, N+1 hydration), not against millisecond drift. In-scope v1
 * behaviour is token-prefix over folded fields (Q7) — mid-word substrings,
 * transliteration and typos are out of scope and only printed, never
 * asserted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WorkSearchSloTest {

    private lateinit var context: Context
    private lateinit var db: AudiobookDatabase
    private lateinit var dao: AudiobookDao

    companion object {
        const val CORPUS_SIZE = 10_000
        /** Generous Robolectric bound — devices answer far faster. */
        const val P95_BOUND_MS = 1_000L
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.audiobookDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private val adjectives = listOf("Тиха", "Гучна", "Далека", "Забута", "Остання", "Перша", "Нічна", "Денна")
    private val nouns = listOf("Ріка", "Гора", "Пісня", "Дорога", "Тінь", "Зоря", "Книга", "Мрія")
    private val surnames = listOf("Шевченко", "Франко", "Українка", "Коцюбинський", "Нечуй", "Мирний", "Стус", "Костенко")

    private fun seed() = runBlocking {
        val works = ArrayList<WorkEntity>(CORPUS_SIZE)
        val sources = ArrayList<WorkSourceEntity>(CORPUS_SIZE)
        for (i in 0 until CORPUS_SIZE) {
            val title = when {
                i == 0 -> "Кобзар"
                i == 1 -> "Лісова пісня"
                i == 2 -> "Тіні забутих предків"
                else -> "${adjectives[i % adjectives.size]} ${nouns[(i / adjectives.size) % nouns.size]} $i"
            }
            val author = when {
                i == 0 -> "Тарас Шевченко"
                i == 1 -> "Леся Українка"
                i == 2 -> "Михайло Коцюбинський"
                else -> "Автор ${surnames[i % surnames.size]} $i"
            }
            val mergeKey = MergeKey.keyFor(title, author)
            val series = if (i % 7 == 0) "Цикл ${i % 80}" else null
            works += WorkEntity(
                id = mergeKey, mergeKey = mergeKey, title = title, author = author,
                seriesTitle = series, addedAt = i.toLong()
            )
            sources += WorkSourceEntity(
                id = "$mergeKey|t${i % 5}|$i", workId = mergeKey,
                sourceId = "t${i % 5}", sourceUrl = "https://t${i % 5}.example/$i",
                addedAt = i.toLong()
            )
        }
        val start = System.nanoTime()
        // The production write door (upsert also refreshes the index row).
        for (k in works.indices) {
            dao.upsertWorkWithSource(works[k], sources[k])
        }
        val writeMs = (System.nanoTime() - start) / 1_000_000
        println("search-slo: indexed $CORPUS_SIZE works in ${writeMs}ms")
    }

    @Test
    fun `quality set answers locally within the bound`() = runBlocking {
        seed()
        assertTrue(dao.workSearchRowCount() == CORPUS_SIZE)

        // In-scope: exact, prefix, multi-token, series, author.
        val expected = listOf(
            "кобзар" to "кобзар|тарас шевченко",
            "кобз" to "кобзар|тарас шевченко",
            "шевч" to "кобзар|тарас шевченко",
            "тарас шевченко" to "кобзар|тарас шевченко",
            "лісова пісня" to "лісова пісня|леся українка",
            "ліс" to "лісова пісня|леся українка",
            "коцюбинський" to "тіні забутих предків|михайло коцюбинський",
            "цикл 7" to null, // any work of Цикл 7 — membership, not identity
            "забута" to null, // adjective shared by ~1/8 of the corpus
            "мрія" to null, // noun shared across the corpus
            "дорога" to null,
            "костенко" to null, // surname family across generated authors
            "нечуй" to null,
            "автор" to null, // every generated author
            "тиха ріка" to null,
            "забутих предків" to null,
            "книга 9008" to null,
            "українка" to "лісова пісня|леся українка"
        )
        // Out of scope (printed only): mid-word substring, translit, typo.
        val observedOnly = listOf("обзар", "shevchenko", "кобсар")

        val timings = ArrayList<Pair<String, Long>>()
        for ((query, wantId) in expected) {
            val match = SearchIndexNormalize.matchQuery(query)!!
            val start = System.nanoTime()
            val ids = dao.matchWorkSearch(match, 50)
            val ms = (System.nanoTime() - start) / 1_000_000
            timings += query to ms
            assertTrue("query '$query' answered empty", ids.isNotEmpty())
            if (wantId != null) {
                assertTrue("query '$query' missed $wantId in ${ids.take(5)}", wantId in ids)
            }
        }
        println("\n=== search-slo quality set (${timings.size} queries, $CORPUS_SIZE works) ===")
        for ((query, ms) in timings.sortedBy { it.second }) {
            println(String.format("%-20s %6d ms", "'$query'", ms))
        }
        val sorted = timings.map { it.second }.sorted()
        val p50 = sorted[(sorted.size * 0.5).toInt()]
        val p95 = sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.size - 1)]
        println("p50=${p50}ms p95=${p95}ms (bound ${P95_BOUND_MS}ms, Robolectric JVM SQLite)")
        assertTrue("p95 ${p95}ms exceeds bound ${P95_BOUND_MS}ms", p95 <= P95_BOUND_MS)

        println("--- out-of-scope observations (not asserted) ---")
        for (query in observedOnly) {
            val match = SearchIndexNormalize.matchQuery(query) ?: continue
            val ids = dao.matchWorkSearch(match, 5)
            println("'$query' -> ${ids.size} hits")
        }
    }
}
