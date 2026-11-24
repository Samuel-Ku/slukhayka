package com.slukhayka.audiobooks.data.collections

import java.io.File
import kotlin.system.measureNanoTime
import com.slukhayka.audiobooks.data.source.GlobalSearchResult
import com.slukhayka.audiobooks.data.source.GlobalSearchSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Pure JVM fixture tests for the spec-16 T1 matcher: exact matches,
 * diacritics/case/punctuation tolerance, annotation trimming, author-only
 * fallback and the empty-match case — no Android, no network, no database.
 */
class CollectionMatcherTest {

    private fun book(title: String, author: String) = GlobalSearchResult(
        title = title,
        author = author,
        mergeKey = "$title|$author",
        sources = listOf(GlobalSearchSource("sluhay", "Sluhay", "https://sluhay.com/$title"))
    )

    private fun entry(author: String, title: String? = null) = CollectionEntry(author, title)

    // --- exact + author+title rule -----------------------------------------

    @Test
    fun `exact author and title match`() {
        val catalog = listOf(book("Старий і море", "Ернест Гемінґвей"))
        val collection = CollectionList("nobel", "Нобелівські лауреати", entries = listOf(entry("Ернест Гемінґвей", "Старий і море")))

        val matched = CollectionMatcher.match(collection, catalog)

        assertEquals(listOf("Старий і море"), matched.books.map { it.title })
    }

    @Test
    fun `a book never appears under an entry it does not match`() {
        val catalog = listOf(book("Старий і море", "Ернест Гемінґвей"))
        val collection = CollectionList("nobel", "Нобелівські лауреати", entries = listOf(entry("Ернест Гемінґвей", "Інша книга")))

        val matched = CollectionMatcher.match(collection, catalog)

        // Author agrees but the title does not — no match, nothing fabricated.
        assertTrue(matched.books.isEmpty())
    }

    @Test
    fun `matching cannot combine the title of one book with the author of another`() {
        val collection = CollectionList("shev", "Шевченко", entries = listOf(entry("Тарас Шевченко", "Кобзар")))
        val catalog = listOf(
            book("Кобзар", "Інший автор"),
            book("Інша книга", "Тарас Шевченко")
        )

        assertTrue(CollectionMatcher.match(collection, catalog).books.isEmpty())
        assertTrue(CollectionMatcher.matchAll(listOf(collection), catalog).isEmpty())
    }

    @Test
    fun `a blank author never matches anything`() {
        val catalog = listOf(book("Старий і море", "Ернест Гемінґвей"))
        val collection = CollectionList("x", "X", entries = listOf(entry("", "Старий і море")))

        assertTrue(CollectionMatcher.match(collection, catalog).books.isEmpty())
    }

    // --- tolerance ----------------------------------------------------------

    @Test
    fun `case and punctuation are tolerated`() {
        val catalog = listOf(book("КОБЗАР!", "Тарас Шевченко"))
        val collection = CollectionList("shev", "Шевченківська премія", entries = listOf(entry("Тарас Шевченко", "кобзар")))

        assertEquals(1, CollectionMatcher.match(collection, catalog).books.size)
    }

    @Test
    fun `diacritics are tolerated`() {
        // Latin-script diacritics fold: «García» ≈ «Garcia», «Márquez» ≈ «Marquez».
        val catalog = listOf(book("Cien anos de soledad", "Gabriel Garcia Marquez"))
        val collection = CollectionList("nobel", "Нобелівські лауреати", entries = listOf(entry("Gabriel García Márquez", "Cien años de soledad")))

        assertEquals(1, CollectionMatcher.match(collection, catalog).books.size)
    }

    @Test
    fun `subtitle is cut like the union merge key`() {
        // The union merges on the MergeKey rule (subtitle cut) — the matcher
        // must agree with it: «Енеїда: поема» is the same Work as «Енеїда».
        val catalog = listOf(book("Енеїда: поема", "Іван Котляревський"))
        val collection = CollectionList("x", "X", entries = listOf(entry("Іван Котляревський", "Енеїда")))

        assertEquals(1, CollectionMatcher.match(collection, catalog).books.size)
    }

    @Test
    fun `parenthetical annotation is trimmed`() {
        val catalog = listOf(book("Кобзар (повне видання)", "Тарас Шевченко"))
        val collection = CollectionList("shev", "Шевченківська премія", entries = listOf(entry("Тарас Шевченко", "Кобзар")))

        assertEquals(1, CollectionMatcher.match(collection, catalog).books.size)
    }

    // --- author-only fallback + empty ---------------------------------------

    @Test
    fun `title-less entry matches every catalog book of that author`() {
        val catalog = listOf(
            book("Маруся Чурай", "Ліна Костенко"),
            book("Берестечко", "Ліна Костенко"),
            book("Собор", "Олесь Гончар")
        )
        val collection = CollectionList("x", "X", entries = listOf(entry("Ліна Костенко")))

        val matched = CollectionMatcher.match(collection, catalog)

        assertEquals(setOf("Маруся Чурай", "Берестечко"), matched.books.map { it.title }.toSet())
    }

    @Test
    fun `entry matching nothing contributes nothing`() {
        val catalog = listOf(book("Собор", "Олесь Гончар"))
        val collection = CollectionList("x", "X", entries = listOf(entry("Неіснуючий Автор", "Неіснуюча книга")))

        val matched = CollectionMatcher.match(collection, catalog)

        assertTrue(matched.books.isEmpty())
    }

    @Test
    fun `matchAll drops empty collections and keeps asset order`() {
        val catalog = listOf(book("Старий і море", "Ернест Гемінґвей"))
        val nobel = CollectionList("nobel", "Нобелівські лауреати", entries = listOf(entry("Ернест Гемінґвей", "Старий і море")))
        val empty = CollectionList("booker", "Букер", entries = listOf(entry("Янн Мартел", "Життя Пі")))

        val matched = CollectionMatcher.matchAll(listOf(nobel, empty), catalog)

        assertEquals(listOf("nobel"), matched.map { it.id })
        assertEquals(1, matched.single().books.size)
    }

    @Test
    fun `matching preserves catalog order repetitions and overlapping entry membership`() {
        val first = book("Кобзар", "Тарас Шевченко")
        val second = book("Гайдамаки", "Тарас Шевченко")
        val catalog = listOf(second, first, second)
        val collection = CollectionList("shev", "Шевченко", "Джерело списку", listOf(
            entry("Тарас Шевченко", "Кобзар"),
            entry("Тарас Шевченко"),
            entry("Тарас Шевченко", "Гайдамаки")
        ))

        assertEquals(catalog, CollectionMatcher.match(collection, catalog).books)
        assertEquals(listOf(collection.id, collection.id),
            CollectionMatcher.matchAll(listOf(collection, collection), catalog).map { it.id })
    }

    @Test
    fun `batch matching agrees with individual entry rules over varied claims`() {
        val random = Random(20261124)
        val authors = listOf("Тарас Шевченко", "  ТАРАС ШЕВЧЕНКО ", "Gabriel García", "Gabriel Garcia", "Їжак Йосип", "Дев&#x27;ятко", "Дев'ятко", "", "  ", "!!!")
        val titles = listOf("Кобзар", "КОБЗАР!", "Кобзар (повне видання)", "Кобзар: поезії", "Cien años", "Cien anos", "Енеїда", "Енеіда", "", "  ", "!!!")
        repeat(100) {
            val catalog = List(random.nextInt(0, 14)) { book(titles.random(random), authors.random(random)) }
            val collections = List(random.nextInt(0, 5)) { index ->
                CollectionList("$index", "Добірка $index", "Джерело $index", List(random.nextInt(0, 7)) {
                    entry(authors.random(random), if (random.nextBoolean()) null else titles.random(random))
                })
            }
            val expected = collections.map { collection ->
                CollectionMatcher.MatchedCollection(collection.id, collection.name, collection.sourceNote,
                    catalog.filter { candidate -> collection.entries.any { CollectionMatcher.entryMatches(it, candidate) } })
            }
            assertEquals(expected.filter { it.books.isNotEmpty() }, CollectionMatcher.matchAll(collections, catalog))
            collections.zip(expected).forEach { (collection, result) ->
                assertEquals(result, CollectionMatcher.match(collection, catalog))
            }
        }
    }

    @Test
    fun `matching rereads changed catalog and collection inputs`() {
        val entries = mutableListOf(entry("Тарас Шевченко", "Кобзар"))
        val collection = CollectionList("shev", "Шевченко", entries = entries)
        val catalog = mutableListOf(book("Кобзар", "Тарас Шевченко"))
        val before = CollectionMatcher.match(collection, catalog)
        entries.clear()
        catalog.add(book("Гайдамаки", "Тарас Шевченко"))

        assertTrue(CollectionMatcher.matchAll(listOf(collection), catalog).isEmpty())
        assertEquals(1, before.books.size)
        entries.add(entry("Тарас Шевченко"))
        assertEquals(catalog, CollectionMatcher.match(collection, catalog).books)
    }

    // --- normalization unit pins --------------------------------------------

    @Test
    fun `normalize folds case punctuation and diacritics`() {
        // Cyrillic stays Cyrillic, case-folded, punctuation dropped.
        assertEquals("габрієль гарсія маркес", CollectionMatcher.normalizeAuthor("Габрієль Гарсія Маркес"))
        // Latin diacritics fold to the base letters.
        assertEquals("gabriel garcia marquez", CollectionMatcher.normalizeAuthor("Gabriel García Márquez"))
        assertEquals("cien anos de soledad", CollectionMatcher.normalizeTitle("Cien años de soledad!"))
    }

    @Test
    fun `normalizeTitle cuts the subtitle`() {
        assertEquals("енеїда", CollectionMatcher.normalizeTitle("Енеїда: поема"))
        assertEquals("кобзар", CollectionMatcher.normalizeTitle("Кобзар (повне видання)"))
    }

    @Test
    fun `entryMatches agrees with the public rule`() {
        assertTrue(CollectionMatcher.entryMatches(entry("Тарас Шевченко", "Кобзар"), book("Кобзар", "Тарас Шевченко")))
        assertTrue(CollectionMatcher.entryMatches(entry("Тарас Шевченко"), book("Гайдамаки", "Тарас Шевченко")))
        assertFalse(CollectionMatcher.entryMatches(entry("Тарас Шевченко", "Кобзар"), book("Гайдамаки", "Тарас Шевченко")))
        assertFalse(CollectionMatcher.entryMatches(entry("", "Кобзар"), book("Кобзар", "Тарас Шевченко")))
    }
}

/** Manual JVM benchmark; run main with the repository root as its argument. */
object RunCollectionMatcherBenchmark {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1) { "Pass the repository root" }
        val assets = File(args.single(), "app/src/main/assets/collections")
        val collections = assets.listFiles().orEmpty().filter { it.extension == "json" }
            .sortedBy { it.name }.map { file ->
                requireNotNull(CollectionJson.decode(file.readText())) { "Invalid collection: $file" }
            }
        val entries = collections.flatMap { it.entries }.distinctBy { it.author to it.title }
        require(entries.isNotEmpty()) { "No collection entries in $assets" }
        var checksum = 0
        for (size in listOf(1, 100, 1000)) {
            // Synthetic records: distinct bundled claims, then unrelated rows.
            // This is not a captured listener's library or UI timing.
            val catalog = List(size) { index ->
                val entry = entries[index % entries.size]
                GlobalSearchResult(
                    title = if (index < entries.size) entry.title ?: "Книга $index" else "Книга $index",
                    author = if (index < entries.size) entry.author else "Інший автор $index",
                    mergeKey = "$index",
                    sources = emptyList()
                )
            }
            fun reference(): List<CollectionMatcher.MatchedCollection> = collections.map { collection ->
                CollectionMatcher.MatchedCollection(collection.id, collection.name, collection.sourceNote,
                    catalog.filter { book -> collection.entries.any { CollectionMatcher.entryMatches(it, book) } })
            }.filter { it.books.isNotEmpty() }
            fun current() = CollectionMatcher.matchAll(collections, catalog)
            check(reference() == current()) { "Results differ for $size Works" }
            repeat(100) { reference(); current() }
            val before = mutableListOf<Double>()
            val after = mutableListOf<Double>()
            fun sample(operation: () -> List<CollectionMatcher.MatchedCollection>): Double =
                measureNanoTime {
                    repeat(10) { checksum += operation().sumOf { it.books.size } }
                } / 10_000_000.0
            repeat(9) { index ->
                if (index % 2 == 0) {
                    before.add(sample(::reference))
                    after.add(sample(::current))
                } else {
                    after.add(sample(::current))
                    before.add(sample(::reference))
                }
            }
            val oldMedian = before.sorted()[before.size / 2]
            val newMedian = after.sorted()[after.size / 2]
            println("syntheticRecords=$size entries=${entries.size} referenceMs=$oldMedian currentMs=$newMedian speedup=${oldMedian / newMedian}")
            println("referenceSamplesMs=$before currentSamplesMs=$after")
        }
        check(checksum > 0)
    }
}
