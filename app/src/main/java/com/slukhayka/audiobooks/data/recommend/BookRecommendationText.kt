package com.slukhayka.audiobooks.data.recommend

/**
 * Stable Work embedding input with conservative cleanup of recognized recording templates.
 * Unsupported, quoted and ambiguous description prose, including individual readers, is retained.
 */
object BookRecommendationText {
    private const val MAX_DESCRIPTION_CHARS = 1_200
    private val html = Regex("<[^>]+>")
    private val quotedHtml = Regex("<\\s*/?\\s*(?:q|blockquote)(?=[\\s/>]|$)", RegexOption.IGNORE_CASE)
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
        val cleanedAuthor = clean(author)
        val cleanedDescription = clean(effectiveDescription)
        val description = if (quotedHtml.containsMatchIn(effectiveDescription)) {
            cleanedDescription
        } else {
            removeRecordingFooter(removeRecordingHeader(cleanedDescription, cleanedTitle, cleanedAuthor), effectiveDescription)
        }
        val fields = listOf(
            cleanedTitle,
            cleanedAuthor,
            clean(genres),
            clean(series),
            description.take(MAX_DESCRIPTION_CHARS)
        ).filter { it.isNotBlank() }.distinct()
        return fields.joinToString("\n")
    }

    private fun removeRecordingHeader(description: String, title: String, author: String): String {
        if (title.isBlank() || author.isBlank()) return description
        val header = "LibriVox recording of $title by $author."
        if (!description.startsWith(header)) return description
        if (description.length > header.length && !description[header.length].isWhitespace()) return description
        val afterHeader = description.substring(header.length).trimStart()
        val volunteerClauses = listOf(
            "Read in English by Librivox volunteers.",
            "Read in English by LibriVox volunteers.",
            "Read by Librivox volunteers.",
            "Read by LibriVox volunteers."
        )
        val clause = volunteerClauses.firstOrNull {
            afterHeader.startsWith(it) &&
                (afterHeader.length == it.length || afterHeader[it.length].isWhitespace())
        }
        return if (clause == null) afterHeader else afterHeader.substring(clause.length).trimStart()
    }

    private fun removeRecordingFooter(description: String, rawDescription: String): String {
        val catalogInstructions = listOf(
            "For further information, including links to online text, reader information, RSS feeds, CD cover or other formats (if available), please go to the LibriVox catalog page for this recording.",
            "For further information, including links to online text, reader information, RSS feeds, CD cover or other formats or languages (if available), please go to the LibriVox catalog page for this recording."
        )
        val invitations = listOf(
            "For more free audio books or to become a volunteer reader, visit LibriVox.org .",
            "For more free audio books or to become a volunteer reader, visit librivox.org ."
        )
        for (instruction in catalogInstructions) {
            val start = description.indexOf(instruction)
            if (start < 0 || start != description.lastIndexOf(instruction) ||
                (start > 0 && !description[start - 1].isWhitespace())) continue
            val prefix = description.substring(0, start).trimEnd()
            if (prefix.lastOrNull() in listOf('"', '\'', '“', '‘', '«', '‹', '„', '‚')) continue
            // Cleaning must not hide quoted/markup/control characters within operation URLs.
            val rawStart = rawDescription.indexOf(instruction)
            if (rawStart < 0 || rawStart != rawDescription.lastIndexOf(instruction)) continue
            if (html.findAll(rawDescription).any { rawStart in it.range }) continue
            val rawPrefix = rawDescription.substring(0, rawStart)
            if (rawPrefix.lastIndexOf('<') > rawPrefix.lastIndexOf('>')) continue
            val rawOperations = rawDescription.substring(rawStart + instruction.length)
            if (Regex("https?://\\S+").findAll(rawOperations).any {
                hasUnsafeUrlCharacters(it.value) || hasAmbiguousUrlEntity(it.value, allowRawQueryAmp = true)
            }) continue
            var remaining = description.substring(start + instruction.length)
            if (!remaining.startsWith(" ")) continue
            remaining = remaining.substring(1)
            if (remaining.startsWith("http://") || remaining.startsWith("https://")) {
                val catalogUrl = remaining.substringBefore(' ')
                if (!isOperationUrl(catalogUrl) || remaining.length == catalogUrl.length) continue
                remaining = remaining.substring(catalogUrl.length + 1)
            }
            val invitation = invitations.firstOrNull {
                remaining == it || remaining.startsWith("$it ")
            } ?: continue
            if (isDownloadSuffix(remaining.substring(invitation.length))) return prefix
        }
        return description
    }

    private fun isDownloadSuffix(suffix: String): Boolean {
        val item = Regex("(?:Download M4B|M4B Audiobook ([0-9]{2})-([0-9]{2})) \\(([0-9]{1,5}(?:\\.[0-9]{1,2})?)(MB|GB)\\)")
        var remaining = suffix
        while (remaining.isNotEmpty()) {
            if (!remaining.startsWith(" ")) return false
            remaining = remaining.substring(1)
            val match = item.matchAt(remaining, 0) ?: return false
            val lower = match.groupValues[1]
            if (lower.isNotEmpty()) {
                val first = lower.toInt()
                val last = match.groupValues[2].toInt()
                if (first < 1 || first >= last || last > 99) return false
            }
            val size = java.math.BigDecimal(match.groupValues[3])
            val maximum = java.math.BigDecimal(if (match.groupValues[4] == "MB") "99999.99" else "100")
            if (size.signum() <= 0 || size > maximum) return false
            remaining = remaining.substring(match.range.last + 1)
            if (remaining.startsWith(" http://") || remaining.startsWith(" https://")) {
                val downloadUrl = remaining.substring(1).substringBefore(' ')
                if (!isOperationUrl(downloadUrl)) return false
                remaining = remaining.substring(downloadUrl.length + 1)
            }
        }
        return true
    }

    private fun hasUnsafeUrlCharacters(token: String): Boolean = token.any {
        it.isWhitespace() || it.isISOControl() ||
            it in listOf('\\', '<', '>', '"', '\'', '“', '”', '‘', '’', '«', '»', '‹', '›', '„', '‚', '`')
    }

    private fun hasAmbiguousUrlEntity(token: String, allowRawQueryAmp: Boolean = false): Boolean {
        val quotation = Regex("&(?:quot|apos|ldquo|rdquo|lsquo|rsquo|laquo|raquo|lsaquo|rsaquo|bdquo|sbquo|prime)", RegexOption.IGNORE_CASE)
        if (quotation.containsMatchIn(token)) return true
        val entity = Regex("&#(?:[xX][0-9a-fA-F]+|[0-9]+);?|&[A-Za-z][A-Za-z0-9]*;")
        return entity.findAll(token).any { match ->
            if (!allowRawQueryAmp || !match.value.equals("&amp;", ignoreCase = true)) {
                true
            } else {
                val queryStart = token.indexOf('?')
                val fragmentStart = token.indexOf('#')
                val next = token.getOrNull(match.range.last + 1)
                // Only the existing single-pass query-separator normalization is allowed.
                queryStart < 0 || match.range.first <= queryStart ||
                    (fragmentStart >= 0 && match.range.first >= fragmentStart) ||
                    next == null || next in listOf('&', '#', ';', '=', '?', '/')
            }
        }
    }

    private fun isOperationUrl(token: String): Boolean {
        if (hasUnsafeUrlCharacters(token) || hasAmbiguousUrlEntity(token)) return false
        val uri = try {
            java.net.URI(token)
        } catch (_: java.net.URISyntaxException) {
            return false
        }
        if (uri.scheme !in listOf("http", "https") || uri.rawUserInfo != null) return false
        if (uri.host !in listOf("librivox.org", "www.librivox.org", "archive.org", "www.archive.org")) return false
        return uri.port == -1 || uri.port == if (uri.scheme == "http") 80 else 443
    }

    private fun clean(value: String): String = value
        .replace(html, " ")
        .replace("&nbsp;", " ", ignoreCase = true)
        .replace("&amp;", "&", ignoreCase = true)
        .replace(whitespace, " ")
        .trim()
}
