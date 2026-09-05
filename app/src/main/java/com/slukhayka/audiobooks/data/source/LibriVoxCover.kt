package com.slukhayka.audiobooks.data.source

/** Archive's item thumbnail, also used by the LibriVox detail page. */
object LibriVoxCover {
    fun forIdentifier(identifier: String): String =
        "https://archive.org/download/$identifier/__ia_thumb.jpg"

    /** Repairs old feed cards without replacing an existing cover claim. */
    fun resolve(existing: String?, sourceId: String, pageUrl: String): String? {
        if (!existing.isNullOrBlank()) return existing
        if (sourceId != "librivox") return existing
        val identifier = Regex("https://archive\\.org/details/([A-Za-z0-9_.-]+)/?(?:[?#].*)?")
            .matchEntire(pageUrl)?.groupValues?.get(1) ?: return existing
        return forIdentifier(identifier)
    }
}
