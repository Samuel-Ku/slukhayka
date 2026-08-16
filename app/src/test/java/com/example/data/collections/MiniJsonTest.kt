package com.example.data.collections

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression tests for the shared [MiniJson] parser. The null-literal cases
 * pin the fix for a genuine parser bug: a JSON `null` inside an array or
 * object used to abort the whole parse (the parser could not tell a JSON
 * null from a parse failure), so valid documents like the translation
 * endpoint's response — `[[["text","src",null,null,10]],null,...]` — came
 * back as `null`.
 */
class MiniJsonTest {

    @Test
    fun `a null literal inside an array parses`() {
        val parsed = MiniJson.parse("""[["a",null,10],null,"uk"]""") as List<*>

        assertEquals(3, parsed.size)
        val segment = parsed[0] as List<*>
        assertEquals("a", segment[0])
        assertNull(segment[1])
        assertEquals(10.0, segment[2])
        assertNull(parsed[1])
        assertEquals("uk", parsed[2])
    }

    @Test
    fun `a null literal inside an object parses as an absent value`() {
        val parsed = MiniJson.parse("""{"search":[{"id":"Q1"},{"id":null}]}""") as Map<*, *>

        val search = parsed["search"] as List<*>
        assertEquals("Q1", (search[0] as Map<*, *>)["id"])
        // A null value reads as absent for typed readers (as? String → null).
        assertNull((search[1] as Map<*, *>)["id"] as? String)
    }

    @Test
    fun `malformed input still yields null`() {
        assertNull(MiniJson.parse("not json"))
        assertNull(MiniJson.parse("""{"a":}"""))
        assertNull(MiniJson.parse("""[1,2,]"""))
    }
}
