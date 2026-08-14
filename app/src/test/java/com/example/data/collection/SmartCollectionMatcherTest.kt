package com.example.data.collection

import com.example.data.catalog.CatalogBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec-16 T1 (#107) — the pure matcher pinned by fixtures: exact matches,
 * diacritics/case/punctuation tolerance, annotation trimming, author-only
 * fallback with its ambiguity rule, the empty-match case, and the
 * at-least-author-agreement invariant. No network, no Android.
 */
class SmartCollectionMatcherTest {

    private fun card(id: String, title: String, author: String) = CatalogBook(
        id = id,
        title = title,
        author = author,
        url = "https://4read.org/$id.html",
        coverImageUrl = null
    )

    private fun spec(id: String = "s", entries: List<Pair<String, String?>>) = CollectionSpec(
        id = id,
        displayName = "Колекція",
        entries = entries.map { CollectionEntry(it.first, it.second) }
    )

    @Test
    fun `exact author plus title matches`() {
        val books = listOf(card("kobzar", "Кобзар", "Тарас Шевченко"))

        val matched = SmartCollectionMatcher.matchCollections(
            listOf(spec(entries = listOf("Тарас Шевченко" to "Кобзар"))),
            books
        )

        assertEquals(listOf("kobzar"), matched.single().books.map { it.id })
    }

    @Test
    fun `transliteration and case differences match`() {
        val books = listOf(
            card("kobzar", "КОБЗАР", "Михайло Коцюбинський"),
            card("lisova", "Лісова пісня", "Леся Українка")
        )

        // Й folded to И, ь dropped, і folded to и — «Коцюбинский» (a common
        // Russian/transliteration spelling) must match «Коцюбинський».
        val matched = SmartCollectionMatcher.matchCollections(
            listOf(spec(entries = listOf("Михаило Коцюбинский" to "Кобзар"))),
            books
        )

        assertEquals(listOf("kobzar"), matched.single().books.map { it.id })
    }

    @Test
    fun `hyphen and combining-mark diacritics fold`() {
        val books = listOf(
            card("sartre", "Нудота", "Жан-Поль Сартр"),
            card("milosz", "Вибрані вірші", "Czesław Miłosz")
        )

        // Hyphen vs space folds; the Latin precomposed é (combining-acute)
        // decomposes and strips — «José Gómez» written without an accent
        // still agrees on author.
        val matched = SmartCollectionMatcher.matchCollections(
            listOf(
                spec(entries = listOf("Жан Поль Сартр" to "Нудота")),
                spec(id = "jose", entries = listOf("José Gómez" to "Вибрані вірші"))
            ),
            books
        )

        assertEquals(listOf("sartre"), matched[0].books.map { it.id })
        // The bare «José» author does not match «Czesław Miłosz» — author
        // agreement is required.
        assertEquals(listOf("sartre"), matched.single().books.map { it.id })
    }

    @Test
    fun `publisher annotations are trimmed`() {
        val books = listOf(
            card("kobzar", "Кобзар (А-БА-БА-ГА-ЛА-МА-ГА)", "Тарас Шевченко")
        )

        val matched = SmartCollectionMatcher.matchCollections(
            listOf(spec(entries = listOf("Тарас Шевченко" to "Кобзар"))),
            books
        )

        assertEquals(listOf("kobzar"), matched.single().books.map { it.id })
    }

    @Test
    fun `author-only fallback matches a single unambiguous book`() {
        val books = listOf(card("kobzar", "Кобзар", "Тарас Шевченко"))

        val matched = SmartCollectionMatcher.matchCollections(
            listOf(spec(entries = listOf("Тарас Шевченко" to null))),
            books
        )

        assertEquals(listOf("kobzar"), matched.single().books.map { it.id })
    }

    @Test
    fun `author-only fallback hides an ambiguous author`() {
        val books = listOf(
            card("kobzar", "Кобзар", "Тарас Шевченко"),
            card("haidamaky", "Гайдамаки", "Тарас Шевченко")
        )

        val matched = SmartCollectionMatcher.matchCollections(
            listOf(spec(entries = listOf("Тарас Шевченко" to null))),
            books
        )

        // Two books by the author, no title to disambiguate — the entry
        // contributes nothing, the collection is absent.
        assertTrue(matched.isEmpty())
    }

    @Test
    fun `a title-carrying entry keeps both editions`() {
        val books = listOf(
            card("kobzar-1", "Кобзар", "Тарас Шевченко"),
            card("kobzar-2", "Кобзар", "Тарас Шевченко")
        )

        val matched = SmartCollectionMatcher.matchCollections(
            listOf(spec(entries = listOf("Тарас Шевченко" to "Кобзар"))),
            books
        )

        // With a title both editions are the same work — all match.
        assertEquals(listOf("kobzar-1", "kobzar-2"), matched.single().books.map { it.id })
    }

    @Test
    fun `author agreement is required - same title different author never matches`() {
        val books = listOf(
            card("kobzar-other", "Кобзар", "Микола Інший"),
            card("kobzar", "Кобзар", "Тарас Шевченко")
        )

        val matched = SmartCollectionMatcher.matchCollections(
            listOf(spec(entries = listOf("Тарас Шевченко" to "Кобзар"))),
            books
        )

        assertEquals(listOf("kobzar"), matched.single().books.map { it.id })
    }

    @Test
    fun `a book never appears under an entry it does not match`() {
        val books = listOf(card("kobzar", "Кобзар", "Тарас Шевченко"))

        val matched = SmartCollectionMatcher.matchCollections(
            listOf(spec(entries = listOf("Леся Українка" to "Кобзар"))),
            books
        )

        assertTrue(matched.isEmpty())
    }

    @Test
    fun `a book matched by several entries appears once`() {
        val books = listOf(card("kobzar", "Кобзар", "Тарас Шевченко"))

        val matched = SmartCollectionMatcher.matchCollections(
            listOf(spec(entries = listOf("Тарас Шевченко" to "Кобзар", "Тарас Шевченко" to null))),
            books
        )

        assertEquals(listOf("kobzar"), matched.single().books.map { it.id })
    }

    @Test
    fun `empty collections are absent and empty input returns nothing`() {
        assertTrue(
            SmartCollectionMatcher.matchCollections(
                listOf(spec(entries = listOf("Ніхто" to "Нічого"))),
                listOf(card("kobzar", "Кобзар", "Тарас Шевченко"))
            ).isEmpty()
        )
        assertTrue(SmartCollectionMatcher.matchCollections(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun `a blank title enables the author-only fallback`() {
        val books = listOf(card("kobzar", "Кобзар", "Тарас Шевченко"))

        // An entry written with an empty title string (not just an absent one)
        // must fall through to the author-only rule, never match on the blank.
        val matched = SmartCollectionMatcher.matchCollections(
            listOf(spec(entries = listOf("Тарас Шевченко" to ""))),
            books
        )

        assertEquals(listOf("kobzar"), matched.single().books.map { it.id })
    }

    @Test
    fun `entries with a blank author never match`() {
        val books = listOf(card("kobzar", "Кобзар", "Тарас Шевченко"))

        val matched = SmartCollectionMatcher.matchCollections(
            listOf(spec(entries = listOf(" " to "Кобзар"))),
            books
        )

        assertTrue(matched.isEmpty())
    }
}