package com.slukhayka.audiobooks.data.metadata

/** Read-only decoding for presentation and recovery comparison; never rewrites stored identity. */
private val TITLE_ENTITY = Regex("&(#(?:[xX][0-9a-fA-F]+|[0-9]+)|amp|quot|apos|nbsp|lt|gt|laquo|raquo|lsquo|rsquo|ldquo|rdquo|ndash|mdash);")
private val TITLE_NAMED_ENTITIES = mapOf(
    "amp" to "&", "quot" to "\"", "apos" to "'", "nbsp" to " ",
    "lt" to "<", "gt" to ">", "laquo" to "«", "raquo" to "»",
    "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”",
    "ndash" to "–", "mdash" to "—"
)

internal fun decodeTitleEntities(value: String): String {
    var title = value
    // Every replacement shortens the string, so nested escaping converges.
    while (true) {
        val decoded = TITLE_ENTITY.replace(title) { match ->
            val entity = match.groupValues[1]
            TITLE_NAMED_ENTITIES[entity] ?: run {
                val digits = entity.removePrefix("#")
                val code = if (digits.startsWith("x", ignoreCase = true)) {
                    digits.drop(1).toIntOrNull(16)
                } else digits.toIntOrNull()
                if (code != null && code in 0x20..0x10ffff && code !in 0xd800..0xdfff) {
                    String(Character.toChars(code))
                } else match.value
            }
        }
        if (decoded == title) return title
        title = decoded
    }
}

