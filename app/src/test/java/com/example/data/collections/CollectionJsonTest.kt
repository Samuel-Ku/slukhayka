package com.example.data.collections

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure JVM fixture tests for the spec-16 T1 asset decoder (no Robolectric,
 * no org.json stubs — the decoder is the repo's own strict parser).
 */
class CollectionJsonTest {

    private val sample = """
        {
          "id": "nobel",
          "name": "Нобелівські лауреати",
          "sourceNote": "Лауреати Нобелівської премії.",
          "entries": [
            { "author": "Сельма Лагерлеф", "title": "Сага про Єсту Берлінга", "note": "1909" },
            { "author": "Томас Манн", "title": "Будденброки" },
            { "author": "Альбер Камю" }
          ]
        }
    """.trimIndent()

    @Test
    fun `decodes the curated asset shape`() {
        val list = CollectionJson.decode(sample)

        assertNotNull(list)
        assertEquals("nobel", list!!.id)
        assertEquals("Нобелівські лауреати", list.name)
        assertEquals("Лауреати Нобелівської премії.", list.sourceNote)
        assertEquals(3, list.entries.size)
        assertEquals("Сельма Лагерлеф", list.entries[0].author)
        assertEquals("Сага про Єсту Берлінга", list.entries[0].title)
        assertEquals("1909", list.entries[0].note)
        // Optional note absent.
        assertEquals("Будденброки", list.entries[1].title)
        assertNull(list.entries[1].note)
        // Author-only entry (title-less).
        assertEquals("Альбер Камю", list.entries[2].author)
        assertNull(list.entries[2].title)
    }

    @Test
    fun `string escapes are honoured`() {
        val list = CollectionJson.decode(
            """{"id":"x","name":"A \"книга\"","sourceNote":"line\nbreak","entries":[{"author":"Автор \"Ім'я\" \u041a"}]}"""
        )
        assertNotNull(list)
        assertEquals("A \"книга\"", list!!.name)
        assertEquals("line\nbreak", list.sourceNote)
        assertEquals("Автор \"Ім'я\" К", list.entries.single().author)
    }

    @Test
    fun `empty entries array is valid`() {
        val list = CollectionJson.decode("""{"id":"e","name":"Порожня","entries":[]}""")
        assertNotNull(list)
        assertEquals(0, list!!.entries.size)
    }

    @Test
    fun `missing entries field is valid and empty`() {
        val list = CollectionJson.decode("""{"id":"e","name":"Без записів"}""")
        assertNotNull(list)
        assertEquals(0, list!!.entries.size)
    }

    @Test
    fun `malformed or invalid input decodes to null`() {
        assertNull(CollectionJson.decode("not json"))
        assertNull(CollectionJson.decode("""{"id": 1, "name": "x"}""")) // wrong value type
        assertNull(CollectionJson.decode("""{"id":"x"}""")) // missing name
        assertNull(CollectionJson.decode("""{"name":"x"}""")) // missing id
        assertNull(CollectionJson.decode("""{"id":"","name":"x"}""")) // blank id
        assertNull(CollectionJson.decode("""{"id":"x","name":"y","entries":[{"author":""}]}""")) // blank author
        assertNull(CollectionJson.decode("""{"id":"x","name":"y","entries":"no"}""")) // entries not an array
        assertNull(CollectionJson.decode("""{"id":"x","name":"y","entries":[{"author":"A"},"bad"]}"""))
        assertNull(CollectionJson.decode("""{"id":"x","name":"y"} trailing""")) // trailing junk
    }

    @Test
    fun `unicode text round-trips`() {
        val list = CollectionJson.decode(
            """{"id":"shev","name":"Шевченківська премія","sourceNote":"Національна премія України імені Тараса Шевченка","entries":[{"author":"Ліна Костенко","title":"Маруся Чурай"}]}"""
        )
        assertNotNull(list)
        assertEquals("Шевченківська премія", list!!.name)
        assertEquals("Маруся Чурай", list.entries.single().title)
    }
}
