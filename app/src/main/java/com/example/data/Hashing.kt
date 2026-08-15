package com.example.data

import java.io.InputStream
import java.security.MessageDigest

/**
 * Content hashing for local imports (wayfinder #48): a copied audio file's
 * SHA-256 lets re-imports of the same bytes be detected and skipped instead
 * of duplicating storage. Exposed as top-level internals so JVM tests can
 * compute fixture hashes with the exact production implementation.
 */
internal const val HASH_BUFFER_SIZE = 64 * 1024

private val HEX_CHARS = "0123456789abcdef".toCharArray()

/** Hex (lowercase) of a byte array; plain-loop impl stays compatible with minSdk 24. */
internal fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    val sb = StringBuilder(digest.size * 2)
    for (b in digest) {
        val v = b.toInt() and 0xFF
        sb.append(HEX_CHARS[v ushr 4]).append(HEX_CHARS[v and 0x0F])
    }
    return sb.toString()
}

/**
 * Streams [input] through SHA-256 and returns the lowercase hex digest.
 * The stream is fully consumed (and closed by the caller's `use`).
 */
internal fun contentHashOf(input: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(HASH_BUFFER_SIZE)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        if (read > 0) digest.update(buffer, 0, read)
    }
    return sha256Hex(digest.digest())
}
