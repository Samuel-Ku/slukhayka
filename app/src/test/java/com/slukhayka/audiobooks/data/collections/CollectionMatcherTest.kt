package com.slukhayka.audiobooks.data.collections

import com.slukhayka.audiobooks.data.source.GlobalSearchResult
import com.slukhayka.audiobooks.data.source.GlobalSearchSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
