package com.slukhayka.audiobooks.ui.catalog

/** Classifies only a small ranged response; it never consumes a whole stream. */
object MediaRangeValidator {
    fun isValid(contentType: String?, prefix: ByteArray): Boolean {
        if (prefix.isEmpty()) return false
        val normalizedType = contentType.orEmpty().substringBefore(';').trim().lowercase()
        val textPrefix = prefix.take(MAX_TEXT_PREFIX_BYTES)
            .toByteArray()
            .decodeToString()
            .trimStart()
            .lowercase()
        if (normalizedType == "text/html" ||
            textPrefix.startsWith("<!doctype html") ||
            textPrefix.startsWith("<html")
        ) return false

        if (normalizedType.startsWith("audio/") || normalizedType.startsWith("video/")) return true
        if (normalizedType in MEDIA_APPLICATION_TYPES) return true
        if (textPrefix.startsWith("#extm3u")) return true

        return prefix.startsWithAscii("ID3") ||
            prefix.startsWithAscii("OggS") ||
            prefix.startsWithAscii("fLaC") ||
            prefix.startsWithAscii("RIFF") ||
            (prefix.size >= 2 && prefix[0].toInt() and 0xff == 0xff && prefix[1].toInt() and 0xe0 == 0xe0)
    }

    private fun ByteArray.startsWithAscii(value: String): Boolean {
        val expected = value.encodeToByteArray()
        return size >= expected.size && expected.indices.all { index -> this[index] == expected[index] }
    }

    private val MEDIA_APPLICATION_TYPES = setOf(
        "application/octet-stream",
        "application/ogg",
        "application/vnd.apple.mpegurl",
        "application/x-mpegurl"
    )
    private const val MAX_TEXT_PREFIX_BYTES = 256
}
