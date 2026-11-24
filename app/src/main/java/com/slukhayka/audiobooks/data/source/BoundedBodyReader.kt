package com.slukhayka.audiobooks.data.source

import java.io.InputStream
import java.nio.charset.Charset

/**
 * #1487 (slice 2) — reads an HTTP body under a hard BYTE cap and decodes it
 * with the response's own charset. Pure and testable: the transport only
 * supplies the stream and the Content-Type charset.
 *
 * The cap is checked BEFORE the body is buffered into a String: a page probe
 * must never pull an unbounded binary into memory just because a listener
 * pasted a link.
 */
object BoundedBodyReader {

    /** Null when the body exceeds [maxBytes]; never throws on a read error. */
    fun read(stream: InputStream, charset: Charset, maxBytes: Int): String? {
        if (maxBytes <= 0) return null
        return stream.use { input ->
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val read = try {
                    input.read(chunk)
                } catch (_: Exception) {
                    return null
                }
                if (read < 0) break
                total += read
                if (total > maxBytes) return null
                buffer.write(chunk, 0, read)
            }
            buffer.toString(charset.name())
        }
    }
}
