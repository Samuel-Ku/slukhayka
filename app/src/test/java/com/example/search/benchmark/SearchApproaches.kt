package com.example.search.benchmark

import android.database.sqlite.SQLiteDatabase

/**
 * Candidate on-device search approaches for the Ukrainian-tolerant search
 * benchmark (wayfinder ticket #51). Pure JVM except [Fts4Approach] (uses
 * Robolectric's bundled SQLite, mirroring what Android ships).
 *
 * These live in the test source set on purpose: the ticket's deliverable is
 * evidence (accuracy / latency / index-size / dependency trade-offs), not a
 * production feature. A future search ticket may promote them into
 * app/src/main after the benchmark picks the winner.
 */
object SearchApproaches {

    // ---------------------------------------------------------------- data
    data class Book(
        val id: String,
        val title: String,
        val author: String,
        val narrator: String = "",
        val series: String = "",
        val genre: String = "",
        val chapterTitles: List<String> = emptyList(),
    ) {
        val searchable: String =
            (listOf(title, author, narrator, series, genre) + chapterTitles).joinToString(" | ")
    }

    // ---------------------------------------------------------- normalization
    /** Light normalization: lowercase, ё→е, apostrophe variants, punctuation. */
    fun lightNormalize(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            when {
                c == 'Ё' || c == 'ё' -> sb.append('е')
                c == '\u02BC' || c == '\u2019' || c == '\u2018' || c == '`' -> sb.append('\'')
                c == '-' || c == '\u2013' || c == '\u2014' || c == '_' -> sb.append(' ')
                !c.isLetterOrDigit() -> sb.append(' ')
                else -> sb.append(c.lowercaseChar())
            }
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Tolerant normalization: light + the classic Ukrainian confusion pairs —
     * і/ї/и→i, е/є→e, ґ/г→г. Handles «Іспит» vs «испит», «Ґенкі» vs «Генкі»,
     * «Живий» vs «Живій». Loses the і/и distinction on purpose.
     */
    fun tolerantNormalize(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            when (c) {
                'і', 'ї', 'И', 'І', 'Ї', 'и' -> sb.append('i')
                'е', 'є', 'Е', 'Є' -> sb.append('e')
                'ґ', 'Ґ' -> sb.append('г')
                else -> sb.append(c)
            }
        }
        return lightNormalize(sb.toString())
    }

    // ------------------------------------------------------------- keyboard
    /** Ukrainian QWERTY layout: Latin char typed on an en keyboard → Cyrillic. */
    private val EN_TO_UK: Map<Char, Char> = mapOf(
        'q' to 'й', 'w' to 'ц', 'e' to 'у', 'r' to 'к', 't' to 'е', 'y' to 'н',
        'u' to 'г', 'i' to 'ш', 'o' to 'щ', 'p' to 'з', '[' to 'х', ']' to 'ї',
        'a' to 'ф', 's' to 'і', 'd' to 'в', 'f' to 'а', 'g' to 'п', 'h' to 'р',
        'j' to 'о', 'k' to 'л', 'l' to 'д', ';' to 'ж', '\'' to 'є',
        'z' to 'я', 'x' to 'ч', 'c' to 'с', 'v' to 'м', 'b' to 'и', 'n' to 'т',
        'm' to 'ь', ',' to 'б', '.' to 'ю', '/' to '.'
    )

    /** True when the query looks like Cyrillic text typed on an en keyboard. */
    fun looksLikeLayoutError(query: String): Boolean {
        val letters = query.filter { it.isLetter() }
        if (letters.isEmpty()) return false
        val latin = letters.count { it in 'a'..'z' || it in 'A'..'Z' }
        return latin.toDouble() / letters.length > 0.7
    }

    /** Map Latin → Cyrillic using the Ukrainian QWERTY layout. */
    fun fixLayout(query: String): String {
        val chars = query.toCharArray()
        val sb = StringBuilder(query.length)
        for (i in chars.indices) {
            val c = chars[i]
            val mapped = EN_TO_UK[c.lowercaseChar()]
            if (mapped == null) {
                sb.append(c)
                continue
            }
            // ',' '.' '\'' are ambiguous: they are punctuation AND the б/ю/є keys.
            // Resolve by context: between letters → Cyrillic letter, else punctuation.
            if (c == ',' || c == '.' || c == '\'') {
                val prevLetter = i > 0 && chars[i - 1].isLetter()
                val nextLetter = i < chars.size - 1 && chars[i + 1].isLetter()
                if (c == '\'' && prevLetter && nextLetter) {
                    sb.append(c) // orthographic apostrophe («До'Урдена»)
                } else if (prevLetter && nextLetter) {
                    sb.append(mapped)
                } else {
                    sb.append(c)
                }
            } else {
                sb.append(mapped)
            }
        }
        return sb.toString()
    }

    // --------------------------------------------------------- transliteration
    /**
     * Ukrainian transliteration as a set of spelling variants: the official
     * 2010 system (и→y, ї→yi at word start) and the legacy system (и→i,
     * й→y) both occur in the wild, so each ambiguous letter contributes all
     * its spellings and the cartesian product is returned (capped at 16).
     */
    fun transliterate(s: String): List<String> {
        val src = s.lowercase()
        fun options(c: Char): List<String> = when (c) {
            'а' -> listOf("a"); 'б' -> listOf("b"); 'в' -> listOf("v"); 'г' -> listOf("h"); 'ґ' -> listOf("g")
            'д' -> listOf("d"); 'е' -> listOf("e"); 'ж' -> listOf("zh"); 'з' -> listOf("z")
            'и' -> listOf("y", "i"); 'і' -> listOf("i"); 'й' -> listOf("i", "y")
            'к' -> listOf("k"); 'л' -> listOf("l"); 'м' -> listOf("m"); 'н' -> listOf("n"); 'о' -> listOf("o")
            'п' -> listOf("p"); 'р' -> listOf("r"); 'с' -> listOf("s"); 'т' -> listOf("t"); 'у' -> listOf("u")
            'ф' -> listOf("f"); 'х' -> listOf("kh"); 'ц' -> listOf("ts"); 'ч' -> listOf("ch")
            'ш' -> listOf("sh"); 'щ' -> listOf("shch")
            'ь' -> listOf("", "'"); 'ю' -> listOf("iu", "yu"); 'я' -> listOf("ia", "ya")
            'є' -> listOf("ie", "ye"); 'ї' -> listOf("i", "yi")
            '\'' -> listOf("'", "")
            else -> listOf(c.toString())
        }
        val parts = src.map { options(it) }
        val result = mutableListOf<String>()
        fun rec(i: Int, acc: StringBuilder) {
            if (i == parts.size) {
                result.add(acc.toString())
                return
            }
            for (opt in parts[i]) {
                acc.append(opt)
                rec(i + 1, acc)
                acc.setLength(acc.length - opt.length)
            }
        }
        rec(0, StringBuilder())
        return result.distinct().let { if (it.size > 16) it.take(16) else it }
    }

    // ------------------------------------------------------------ Levenshtein
    /** Classic Levenshtein (Wagner–Fischer, two rows). */
    fun levenshtein(a: String, b: String): Int {
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val tmp = prev; prev = cur; cur = tmp
        }
        return prev[b.length]
    }

    // -------------------------------------------------------------- approaches
    /**
     * A. Raw substring — emulates SQLite `LIKE '%q%'` on Cyrillic: SQLite's
     * LIKE is ASCII-only case-insensitive, so Cyrillic matches are
     * case-sensitive. This is what the current server-side DLE search
     * approximates for on-device terms.
     */
    object RawLike : Approach("A. Raw LIKE (case-sensitive)") {
        private var corpus: List<Book> = emptyList()
        override fun build(books: List<Book>) {
            corpus = books
        }

        override fun search(query: String, limit: Int): List<String> {
            val q = query.trim()
            if (q.isEmpty()) return emptyList()
            return corpus.filter { it.title.contains(q) || it.author.contains(q) }
                .take(limit).map { it.id }
        }
    }

    /** B. Light-normalized substring index (in-memory). */
    class NormalizedContains(private val tolerant: Boolean) :
        Approach(if (tolerant) "C. Tolerant-normalized contains" else "B. Light-normalized contains") {
        private lateinit var index: List<Pair<Book, String>>
        override fun build(books: List<Book>) {
            index = books.map { b ->
                val norm = if (tolerant) tolerantNormalize(b.searchable) else lightNormalize(b.searchable)
                b to norm
            }
        }

        override fun search(query: String, limit: Int): List<String> {
            val q = if (tolerant) tolerantNormalize(query) else lightNormalize(query)
            if (q.isEmpty()) return emptyList()
            val tokens = q.split(" ")
            return index.filter { (_, norm) -> tokens.all { it in norm } }
                .take(limit).map { it.first.id }
        }
    }

    /** D. Layout-corrected: fix en→uk keyboard, then tolerant contains. */
    class LayoutCorrected : Approach("D. Layout-corrected contains") {
        private lateinit var index: List<Pair<Book, String>>
        override fun build(books: List<Book>) {
            index = books.map { b -> b to tolerantNormalize(b.searchable) }
        }

        override fun search(query: String, limit: Int): List<String> {
            val q = tolerantNormalize(if (looksLikeLayoutError(query)) fixLayout(query) else query)
            if (q.isEmpty()) return emptyList()
            val tokens = q.split(" ")
            return index.filter { (_, norm) -> tokens.all { it in norm } }
                .take(limit).map { it.first.id }
        }
    }

    /**
     * E. Transliteration index: Cyrillic→Latin spelling-variant set, queried
     * in Latin. Light-normalize (NOT tolerant — tolerant would collapse и
     * before transliteration can emit both и→y and и→i spellings).
     */
    class TranslitIndex : Approach("E. Translit (Latin) index") {
        private lateinit var index: List<Pair<Book, List<String>>>

        override fun build(books: List<Book>) {
            index = books.map { b -> b to transliterate(lightNormalize(b.searchable)) }
        }

        override fun search(query: String, limit: Int): List<String> {
            val q = lightNormalize(query)
            if (q.isEmpty()) return emptyList()
            val qVariants = transliterate(q)
            return index.filter { (_, idxVariants) ->
                qVariants.any { qv ->
                    qv.split(" ").all { w ->
                        idxVariants.any { iv -> iv.split(" ").any { it.contains(w) } }
                    }
                }
            }.take(limit).map { it.first.id }
        }
    }

    /** F. Edit-distance ranking over tolerant-normalized tokens (top-k). */
    class EditDistance : Approach("F. Levenshtein ranked") {
        private lateinit var index: List<Pair<Book, String>>
        override fun build(books: List<Book>) {
            index = books.map { b -> b to tolerantNormalize(b.searchable) }
        }

        override fun search(query: String, limit: Int): List<String> {
            val q = tolerantNormalize(query).split(" ").filter { it.isNotEmpty() }
            if (q.isEmpty()) return emptyList()
            return index
                .map { (book, norm) ->
                    val normTokens = norm.split(" ").filter { it.isNotEmpty() }
                    val score = q.minOf { qq ->
                        normTokens.minOf { nt -> levenshtein(qq, nt) }
                    }
                    book.id to score
                }
                .sortedWith(compareBy({ it.second }, { it.first }))
                .take(limit).map { it.first }
        }
    }

    /**
     * G. FTS4 over a pre-normalized (tolerant) index — the practical Room-FTS
     * shape: normalize on write, prefix-token MATCH on read. FTS5/unicode61 is
     * NOT available in Robolectric's bundled SQLite (probe), but is on real
     * Android system SQLite — see the benchmark notes.
     */
    class Fts4 : Approach("G. FTS4 (normalized index)") {
        private var db: SQLiteDatabase? = null
        private lateinit var bookIds: List<String>

        override fun build(books: List<Book>) {
            val d = SQLiteDatabase.create(null)
            d.execSQL("CREATE VIRTUAL TABLE books_fts USING fts4(title, author, narrator, series, genre)")
            bookIds = books.map { b ->
                val fields = listOf(
                    tolerantNormalize(b.title),
                    tolerantNormalize(b.author),
                    tolerantNormalize(b.narrator),
                    tolerantNormalize(b.series),
                    tolerantNormalize(b.genre)
                )
                d.execSQL(
                    "INSERT INTO books_fts(title, author, narrator, series, genre) VALUES (?,?,?,?,?)",
                    fields.toTypedArray()
                )
                b.id
            }
            db = d
        }

        override fun search(query: String, limit: Int): List<String> {
            val d = db ?: return emptyList()
            val q = tolerantNormalize(query).split(" ").filter { it.isNotEmpty() }
            if (q.isEmpty()) return emptyList()
            val match = q.joinToString(" ") { "\"$it\"*" }
            val cur = d.rawQuery(
                "SELECT rowid FROM books_fts WHERE books_fts MATCH ? ORDER BY rowid LIMIT ?",
                arrayOf(match, limit.toString())
            )
            val out = mutableListOf<String>()
            while (cur.moveToNext()) out.add(bookIds[cur.getInt(0) - 1])
            cur.close()
            return out
        }

        fun close() {
            db?.close()
            db = null
        }
    }

    abstract class Approach(val name: String) {
        abstract fun build(books: List<Book>)
        abstract fun search(query: String, limit: Int): List<String>
    }

    /** Build every approach once against the corpus. */
    fun buildAll(books: List<Book>): List<Approach> = listOf(
        RawLike,
        NormalizedContains(tolerant = false),
        NormalizedContains(tolerant = true),
        LayoutCorrected(),
        TranslitIndex(),
        EditDistance(),
        Fts4()
    ).also { it.forEach { a -> a.build(books) } }
}
