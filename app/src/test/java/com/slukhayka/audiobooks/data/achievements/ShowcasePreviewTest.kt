package com.slukhayka.audiobooks.data.achievements

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #705 (T7) — the confirmation the listener actually gives.
 *
 * The ticket's first criterion is «лише за явним вибором людини», and #691 set
 * the shape of that choice for collections: show exactly what travels, or
 * refuse honestly. These are the showcase's version of those two outcomes.
 */
class ShowcasePreviewTest {

    private val names = mapOf(
        "first_book" to "Нагорода: Перша книга",
        "books_25" to "Нагорода: Прочитано 25 книг"
    )
    private val nameOf: (String) -> String? = { names[it] }

    /** Stand-in for the published limit, so this package need not import it. */
    private val maxPseudonym = 40

    @Test
    fun `a showcase with awards previews exactly those awards, by name`() {
        val preview = ShowcasePreviewFactory.of(listOf("first_book", "books_25"), "Мандрівник", maxPseudonym, nameOf)

        assertNotNull(preview)
        assertEquals("Мандрівник", preview!!.pseudonym)
        assertEquals(2, preview.awardCount)
        assertEquals(
            "слухач мусить бачити, ЯКІ нагороди підуть — інакше згода порожня",
            listOf("Нагорода: Перша книга", "Нагорода: Прочитано 25 книг"),
            preview.awards.map { it.name }
        )
    }

    /**
     * Nothing on the showcase means nothing to publish. Returning a preview
     * here would let a listener confirm and believe they had published
     * something while nothing travelled.
     */
    @Test
    fun `an empty showcase cannot be published`() {
        assertNull(ShowcasePreviewFactory.of(emptyList(), "Мандрівник", maxPseudonym, nameOf))
    }

    /** An id this build cannot name is not something it may ask consent for. */
    @Test
    fun `a showcase of only unknown awards cannot be published`() {
        assertNull(ShowcasePreviewFactory.of(listOf("from_a_newer_version"), "Мандрівник", maxPseudonym, nameOf))
    }

    @Test
    fun `a blank pseudonym cannot be published`() {
        assertNull(ShowcasePreviewFactory.of(listOf("first_book"), "   ", maxPseudonym, nameOf))
    }

    @Test
    fun `the pseudonym is trimmed`() {
        val preview = ShowcasePreviewFactory.of(listOf("first_book"), "  Мандрівник  ", maxPseudonym, nameOf)

        assertEquals("Мандрівник", preview!!.pseudonym)
    }

    @Test
    fun `an over-long pseudonym is clamped to the published limit`() {
        val preview = ShowcasePreviewFactory.of(listOf("first_book"), "я".repeat(100), maxPseudonym, nameOf)

        assertEquals(maxPseudonym, preview!!.pseudonym.length)
    }

    /** The cap still holds through the preview: consent cannot exceed it. */
    @Test
    fun `the preview never offers more than three awards`() {
        val many = mapOf("a" to "A", "b" to "B", "c" to "C", "d" to "D")
        val preview = ShowcasePreviewFactory.of(listOf("a", "b", "c", "d"), "Мандрівник", maxPseudonym) { many[it] }

        assertEquals(ShowcasePublication.MAX_PUBLISHED, preview!!.awardCount)
    }
}
