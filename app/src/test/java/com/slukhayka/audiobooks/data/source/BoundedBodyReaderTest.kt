package com.slukhayka.audiobooks.data.source

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #1487 (slice 2) — the transport's bounded text read: the byte cap is applied
 * BEFORE the body becomes a String, and the response charset is honoured.
 */
class BoundedBodyReaderTest {

    @Test
    fun `reads a body under the cap`() {
        val body = "Привіт, книго!".toByteArray(Charsets.UTF_8)

        assertEquals(
            "Привіт, книго!",
            BoundedBodyReader.read(ByteArrayInputStream(body), Charsets.UTF_8, maxBytes = 1024)
        )
    }

    @Test
    fun `a body over the cap is refused, not truncated`() {
        val body = ByteArray(2048) { 'a'.code.toByte() }

        assertNull(BoundedBodyReader.read(ByteArrayInputStream(body), Charsets.UTF_8, maxBytes = 1024))
        // The boundary itself is allowed (cap is a maximum, not a strict lower bound).
        assertEquals(
            2048,
            BoundedBodyReader.read(ByteArrayInputStream(body), Charsets.UTF_8, maxBytes = 2048)!!.length
        )
    }

    @Test
    fun `the response charset is honoured`() {
        // A windows-1251 page: the same bytes are mojibake under UTF-8.
        val cyrillic = "Книга".toByteArray(Charset.forName("windows-1251"))

        assertEquals(
            "Книга",
            BoundedBodyReader.read(ByteArrayInputStream(cyrillic), Charset.forName("windows-1251"), 1024)
        )
    }

    @Test
    fun `an empty body is an empty string and a read error is null`() {
        assertEquals(
            "",
            BoundedBodyReader.read(ByteArrayInputStream(ByteArray(0)), Charsets.UTF_8, 1024)
        )
        val failing = object : InputStream() {
            override fun read(): Int = throw IOException("boom")
            override fun read(b: ByteArray, off: Int, len: Int): Int = throw IOException("boom")
        }
        assertNull(BoundedBodyReader.read(failing, Charsets.UTF_8, 1024))
    }

    @Test
    fun `a non-positive cap reads nothing`() {
        val body = "x".toByteArray()
        assertNull(BoundedBodyReader.read(ByteArrayInputStream(body), Charsets.UTF_8, maxBytes = 0))
        assertNull(BoundedBodyReader.read(ByteArrayInputStream(body), Charsets.UTF_8, maxBytes = -1))
    }
}
