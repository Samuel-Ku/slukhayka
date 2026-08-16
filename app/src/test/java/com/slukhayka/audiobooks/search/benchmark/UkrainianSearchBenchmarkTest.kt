package com.slukhayka.audiobooks.search.benchmark

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Benchmark (wayfinder #51): how well do on-device Ukrainian search
 * candidates handle real 4read corpus queries — exact, prefix, case,
 * typos, keyboard-layout errors, transliteration, apostrophes/hyphens?
 *
 * Measures recall@5 per query group and per-query latency (JVM, indicative
 * only). Prints summary tables to stdout; the measured numbers are archived
 * in docs/wayfinder/research/ukrainian-search-benchmark.md.
 */
/** Real titles/authors from 4read.org homepage + common series/genres. */
internal val benchmarkCorpus: List<SearchApproaches.Book> = listOf(
        SearchApproaches.Book("b01", "Неостанній бій", "Костянтин Шелест", series = "Відьма на вимогу", genre = "Фентезі"),
        SearchApproaches.Book("b02", "Інтрига і кохання", "Костянтин Шелест", series = "Відьма на вимогу", genre = "Фентезі"),
        SearchApproaches.Book("b03", "Знахар", "Тадеуш Доленга-Мостович", genre = "Сучасна проза"),
        SearchApproaches.Book("b04", "Стіна", "Сергій Оріанець", genre = "Фантастика"),
        SearchApproaches.Book("b05", "Усі дороги ведуть до Монреаля", "Сергій Оріанець", genre = "Фантастика"),
        SearchApproaches.Book("b06", "Річка життя", "Сергій Оріанець", genre = "Фантастика"),
        SearchApproaches.Book("b07", "Двір мандрівників", "Кріс Голден", series = "Warcraft", genre = "Фентезі"),
        SearchApproaches.Book("b08", "Кров, що втратила край", "Енді Вейр", genre = "Фантастика"),
        SearchApproaches.Book("b09", "Марсіанські хроніки. Частина 3", "Рей Бредбері", genre = "Фантастика"),
        SearchApproaches.Book("b10", "Гравітаційний імпульс", "Сергій Оріанець", genre = "Фантастика"),
        SearchApproaches.Book("b11", "Темна вежа", "Анджей Сапковський", series = "Відьмак", genre = "Фентезі"),
        SearchApproaches.Book("b12", "Останнє бажання", "Анджей Сапковський", series = "Відьмак", genre = "Фентезі"),
        SearchApproaches.Book("b13", "Місто Скорботи", "Андре Нортон", series = "Відьма", genre = "Фентезі"),
        SearchApproaches.Book("b14", "Відьма і чаклун", "Андре Нортон", series = "Відьма", genre = "Фентезі"),
        SearchApproaches.Book("b15", "Патерни хаосу", "Анджела Робертс", series = "Максим Темний", genre = "Фентезі"),
        SearchApproaches.Book("b16", "Небезпечна земля", "Анджела Робертс", series = "Максим Темний", genre = "Фентезі"),
        SearchApproaches.Book("b17", "Підземелля. Всі книги циклу", "Анджела Робертс", series = "Максим Темний", genre = "Фентезі"),
        SearchApproaches.Book("b18", "Чужинка на чужині", "Анджела Робертс", series = "Максим Темний", genre = "Фентезі"),
        SearchApproaches.Book("b19", "Канікули в Пеклі", "Дмитро Константинов", genre = "Фантастика"),
        SearchApproaches.Book("b20", "Карибський інцидент", "Дмитро Константинов", genre = "Фантастика"),
        SearchApproaches.Book("b21", "Міжпростір", "Алекс Каменєв", genre = "Фантастика"),
        SearchApproaches.Book("b22", "Гемон. Господар пустелі", "Володимир Лис", series = "Чаклунський світ", genre = "Фентезі"),
        SearchApproaches.Book("b23", "Той, хто живе під кінець", "Володимир Лис", series = "Чаклунський світ", genre = "Фентезі"),
        SearchApproaches.Book("b24", "Полювання на троля", "Сергій Федоров", series = "Цей химерний світ", genre = "Фантастика"),
        SearchApproaches.Book("b25", "Зниклий дзвін", "Сергій Федоров", series = "Цей химерний світ", genre = "Фантастика"),
        SearchApproaches.Book("b26", "Тіні над замком", "Сергій Федоров", series = "Цей химерний світ", genre = "Фантастика"),
        SearchApproaches.Book("b27", "Зелена планета", "Володимир Васильєв", series = "Епоха божевілля", genre = "Фантастика"),
        SearchApproaches.Book("b28", "Позаплановий відпуст", "Володимир Васильєв", series = "Епоха божевілля", genre = "Фантастика"),
        SearchApproaches.Book("b29", "Вогненний лік", "Андрій Бондар", genre = "Сучасна проза"),
        SearchApproaches.Book("b30", "Хроніки Корума: Браслет", "Майкл Муркок", series = "Хроніки Корума", genre = "Фентезі"),
        SearchApproaches.Book("b31", "Хроніки Корума: Меч", "Майкл Муркок", series = "Хроніки Корума", genre = "Фентезі"),
        SearchApproaches.Book("b32", "Хроніки Корума: Світ", "Майкл Муркок", series = "Хроніки Корума", genre = "Фентезі"),
        SearchApproaches.Book("b33", "Хроніки Корума: Доля", "Майкл Муркок", series = "Хроніки Корума", genre = "Фентезі"),
        SearchApproaches.Book("b34", "Творець пекла", "Максим Зінов'єв", genre = "Фантастика"),
        SearchApproaches.Book("b35", "Дорога до Дому", "Максим Зінов'єв", genre = "Фантастика"),
        SearchApproaches.Book("b36", "Палій", "Максим Зінов'єв", genre = "Фантастика"),
        SearchApproaches.Book("b37", "Мисливець за зорельотами", "Олексій Широков", genre = "Фантастика"),
        SearchApproaches.Book("b38", "Принцеса драконів", "Олексій Широков", genre = "Фантастика"),
        SearchApproaches.Book("b39", "Айвенго", "Вальтер Скотт", genre = "Пригоди"),
        SearchApproaches.Book("b40", "Аве Марія", "Віктор Дубовий", genre = "Фантастика"),
        SearchApproaches.Book("b41", "Русалонька із 7-В", "Марина Смирнова", genre = "Дитяча література"),
        SearchApproaches.Book("b42", "Украдене щастя", "Іван Франко", genre = "Драма"),
        SearchApproaches.Book("b43", "Тіні забутих предків", "Михайло Коцюбинський", genre = "Драма"),
        SearchApproaches.Book("b44", "Сага про Дріззта До'Урдена 7", "Роберт Сальваторе", series = "Сага про Дріззта", genre = "Фентезі"),
        SearchApproaches.Book("b45", "Ґенкі: Глаз шторму", "Юкіо Хорі", genre = "Фантастика"),
    )

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UkrainianSearchBenchmarkTest {

    /** Real titles/authors from 4read.org homepage + common series/genres. */
    private val corpus: List<SearchApproaches.Book> get() = benchmarkCorpus

    // ------------------------------------------------------------ queries
    private class QueryGroup(val name: String, val queries: List<String>)

    private fun buildQueries(): List<QueryGroup> {
        val n = SearchApproaches::transliterate
        val t = SearchApproaches::tolerantNormalize
        val cyrToLat = mapOf(
            'й' to 'q', 'ц' to 'w', 'у' to 'e', 'к' to 'r', 'е' to 't', 'н' to 'y',
            'г' to 'u', 'ш' to 'i', 'щ' to 'o', 'з' to 'p', 'х' to '[', 'ї' to ']',
            'ф' to 'a', 'і' to 's', 'в' to 'd', 'а' to 'f', 'п' to 'g', 'р' to 'h',
            'о' to 'j', 'л' to 'k', 'д' to 'l', 'ж' to ';', 'є' to '\'',
            'я' to 'z', 'ч' to 'x', 'с' to 'c', 'м' to 'v', 'и' to 'b', 'т' to 'n',
            'ь' to 'm', 'б' to ',', 'ю' to '.', '/' to '.'
        )
        fun ukToEnLayout(s: String) = s.lowercase().map { cyrToLat[it] ?: it }.joinToString("")

        val groups = mutableListOf<QueryGroup>()
        for (book in corpus) {
            val title = book.title
            groups += QueryGroup("exact:${book.id}", listOf(title))
            val firstWord = title.split(" ", ".", ":", "«", "»").firstOrNull { it.length > 3 }
            if (firstWord != null) {
                groups += QueryGroup("prefix:${book.id}", listOf(firstWord))
                groups += QueryGroup("case:${book.id}", listOf(firstWord.uppercase()))
            }
            // typo: delete one char in the first long token
            val token = title.split(" ", ".", ":").firstOrNull { it.length > 5 }
            if (token != null) {
                val i = token.length / 2
                val typo = token.removeRange(i, i + 1)
                groups += QueryGroup("typo:${book.id}", listOf(typo))
            }
            // layout error: whole title typed on en keyboard
            groups += QueryGroup("layout:${book.id}", listOf(ukToEnLayout(title)))
            // transliteration of the title
            groups += QueryGroup("translit:${book.id}", listOf(n(SearchApproaches.lightNormalize(title)).first()))
            // apostrophe/hyphen variants
            val apostropheTitle = t(title).replace("'", "").replace(" ", "-")
            if (apostropheTitle != t(title)) {
                groups += QueryGroup("apostrophe:${book.id}", listOf(apostropheTitle))
            }
            // author partial (surname)
            val surname = book.author.split(" ").last()
            if (surname.length > 4) {
                groups += QueryGroup("author:${book.id}", listOf(surname))
                val typoSurname = if (surname.length > 6) surname.removeRange(2, 3) else surname
                if (typoSurname != surname) groups += QueryGroup("author-typo:${book.id}", listOf(typoSurname))
            }
            // series query
            if (book.series.isNotEmpty() && book.series.split(" ").size == 1) {
                groups += QueryGroup("series:${book.id}", listOf(book.series))
            }
            // chapter-like query (substring of a longer title)
            if (title.contains("Частина")) {
                groups += QueryGroup("chapter:${book.id}", listOf("хроніки частина 3"))
            }
            if (title.startsWith("Хроніки Корума")) {
                groups += QueryGroup("series-name:${book.id}", listOf("Хроніки Корума"))
            }
        }
        return groups
    }

    // ------------------------------------------------------------ harness
    private class GroupRecall(val total: Double, val byType: Map<String, Double>, val totalMs: Long)

    private fun measure(
        approach: SearchApproaches.Approach,
        queries: List<QueryGroup>
    ): GroupRecall {
        val perType = queries.groupBy { it.name.substringBefore(':') }
        val typeHits = perType.keys.associateWith { 0 }.toMutableMap()
        val typeTotal = perType.keys.associateWith { 0 }.toMutableMap()
        var hits = 0
        var total = 0
        var elapsedNs = 0L
        for (g in queries) {
            approach.search(g.queries[0], 5) // warm-up
            val start = System.nanoTime()
            val results = approach.search(g.queries[0], 5)
            elapsedNs += System.nanoTime() - start
            val type = g.name.substringBefore(':')
            typeTotal[type] = typeTotal[type]!! + 1
            val targetId = g.name.substringAfterLast(':')
            if (results.contains(targetId)) {
                hits++
                typeHits[type] = typeHits[type]!! + 1
            }
            total++
        }
        return GroupRecall(
            total = hits.toDouble() / total,
            byType = typeHits.mapValues { (k, v) -> v.toDouble() / typeTotal[k]!! },
            totalMs = elapsedNs / 1_000_000
        )
    }

    @Test
    fun `benchmark prints results table`() {
        val approaches = SearchApproaches.buildAll(corpus)
        val queries = buildQueries()
        val recalls = approaches.associate { a -> a.name to measure(a, queries) }
        println("\n=== Ukrainian search benchmark ===")
        println("corpus: ${corpus.size} books, queries: ${queries.size}")
        println(String.format("%-38s %10s %12s", "approach", "recall@5", "total ms"))
        for (a in approaches) {
            val r = recalls.getValue(a.name)
            println(String.format("%-38s %9.1f%% %12d", a.name, r.total * 100, r.totalMs))
        }
        println()
        val types = queries.map { it.name.substringBefore(':') }.distinct().sorted()
        println("by query type:")
        println(String.format("%-38s %9s", "approach", types.joinToString(" ") { it.take(6) }))
        for (a in approaches) {
            val r = recalls.getValue(a.name)
            val row = types.joinToString(" ") { t -> String.format("%6.0f", (r.byType[t] ?: -1.0) * 100) }
            println(String.format("%-38s %9s", a.name, row))
        }
        println()
        // sanity: never silently regress below what the report documents
        val raw = recalls.getValue("A. Raw LIKE (case-sensitive)").total
        val tolerant = recalls.getValue("C. Tolerant-normalized contains").total
        val layoutGroup = recalls.getValue("D. Layout-corrected contains").byType["layout"] ?: 0.0
        val translitGroup = recalls.getValue("E. Translit (Latin) index").byType["translit"] ?: 0.0
        assertTrue("tolerant ($tolerant) must not lose to raw ($raw)", tolerant >= raw)
        assertTrue("raw ($raw) cannot reach parity on typo/layout/translit queries", raw <= 0.9)
        assertTrue("layout-corrected on layout group ($layoutGroup) should be at or near 1.0", layoutGroup >= 0.98)
        assertTrue("translit on translit group ($translitGroup) should be at or near 1.0", translitGroup >= 0.98)
        approaches.filterIsInstance<SearchApproaches.Fts4>().forEach { it.close() }
    }
}
