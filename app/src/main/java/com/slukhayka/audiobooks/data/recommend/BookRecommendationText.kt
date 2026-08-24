package com.slukhayka.audiobooks.data.recommend

/** Stable Work-level embedding input; narrator/Edition data is intentionally absent. */
object BookRecommendationText {
    private const val MAX_DESCRIPTION_CHARS = 1_200
    private val html = Regex("<[^>]+>")
    private val whitespace = Regex("\\s+")
    private val sourceSuffix = Regex("\\s*[-–—|]\\s*([Аа]удіокниг(?:а|и)?|[Сс]лухати онлайн|4read).*$")

    fun build(
        title: String,
        author: String,
        genres: String = "",
        series: String = "",
        effectiveDescription: String = ""
    ): String {
        val cleanedTitle = clean(title).replace(sourceSuffix, "").trim()
        val fields = listOf(
            cleanedTitle,
            clean(author),
            clean(genres),
            clean(series),
            clean(effectiveDescription).take(MAX_DESCRIPTION_CHARS)
        ).filter { it.isNotBlank() }.distinct()
        return fields.joinToString("\n")
    }

    private fun clean(value: String): String = value
        .replace(html, " ")
        .replace("&nbsp;", " ", ignoreCase = true)
        .replace("&amp;", "&", ignoreCase = true)
        .replace(whitespace, " ")
        .trim()
}
