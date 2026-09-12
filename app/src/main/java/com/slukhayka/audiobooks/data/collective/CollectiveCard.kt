package com.slukhayka.audiobooks.data.collective

import com.slukhayka.audiobooks.data.merge.MergeKey
import com.slukhayka.audiobooks.data.source.SourceAccessMode
import com.slukhayka.audiobooks.data.source.SourceAccessPolicy
import com.slukhayka.audiobooks.data.source.SourceRegistry
import com.slukhayka.audiobooks.data.source.sourceIdForUrl

/**
 * #522 / ADR-0028 — one VERIFIED public catalogue card shared between
 * installs: the bibliographic identity plus the source that really serves it.
 * The contract is pure Kotlin (no Firebase types) so the transport can be
 * swapped and the bounds are unit-testable.
 *
 * It carries ONLY public facts a source page already showed. It must never
 * carry a search query, a contributor identity, listening history, position,
 * bookmarks, downloads, cookies, session headers, audio bytes or track URLs.
 */
data class CollectiveCardPublication(
    /** The catalogue source's stable id (must match the URL's own source). */
    val sourceId: String,
    /** The canonical public book-page URL on that source. */
    val sourceUrl: String,
    val title: String,
    val author: String,
    val narrator: String = "",
    /** BCP-47 primary tag of the narration ("" = unknown, never guessed). */
    val language: String = "",
    val coverUrl: String? = null,
    val seriesTitle: String? = null,
    val seriesIndex: Int? = null,
    /** The source page's claimed total duration, when it showed one. */
    val durationSeconds: Long? = null,
    /** The source page's claimed chapter count, when it showed one. */
    val chapterCount: Int? = null,
    /** When the publishing install actually observed the verified card. */
    val observedAt: Long
) {
    /** The Work identity the local mirror merges on. */
    val mergeKey: String get() = MergeKey.keyFor(title, author)
}

/**
 * #522 — the acceptance bounds of a collective card. A contribution is
 * accepted only from a DIRECT, non-scam, Ukrainian catalogue source (registry
 * data, never a source-id literal) whose id matches the URL, with every
 * public field inside its bound. Anything else is rejected whole — the
 * channel never sanitizes a malformed card into a half-fact.
 */
object CollectiveCardLimits {

    const val MAX_TITLE = 300
    const val MAX_AUTHOR = 200
    const val MAX_NARRATOR = 200
    const val MAX_URL = 2_000
    const val MAX_SERIES_TITLE = 300
    const val MAX_LANGUAGE = 16

    /** The stable source id of a card's URL, or "unknown"/"local". */
    fun sourceIdOf(url: String): String = sourceIdForUrl(url)

    fun isPublishable(card: CollectiveCardPublication): Boolean {
        if (card.title.isBlank() || card.title.length > MAX_TITLE) return false
        if (card.author.isBlank() || card.author.length > MAX_AUTHOR) return false
        if (card.narrator.length > MAX_NARRATOR) return false
        if (card.sourceUrl.isBlank() || card.sourceUrl.length > MAX_URL) return false
        if ((card.coverUrl?.length ?: 0) > MAX_URL) return false
        if ((card.seriesTitle?.length ?: 0) > MAX_SERIES_TITLE) return false
        if (card.language.length > MAX_LANGUAGE) return false
        if ((card.durationSeconds ?: 0L) < 0L) return false
        if ((card.chapterCount ?: 0) < 0) return false
        if (card.observedAt <= 0L) return false

        val sourceId = sourceIdForUrl(card.sourceUrl)
        if (sourceId != card.sourceId) return false
        if (SourceRegistry.isScam(sourceId)) return false
        if (SourceAccessPolicy.modeFor(sourceId) != SourceAccessMode.DIRECT) return false
        // The first vertical slice is the DIRECT Ukrainian catalogue sources;
        // the registry owns the language claim, never a hardcoded list.
        if (SourceRegistry.contentLanguage(sourceId) != "uk") return false
        if (card.language.isNotBlank() && card.language != "uk") return false
        return true
    }
}
