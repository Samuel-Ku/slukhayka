package com.slukhayka.audiobooks.data.ingest

import com.slukhayka.audiobooks.data.metadata.SubmissionCandidate
import java.security.MessageDigest

/**
 * Moderation T7 (#840) — the ONE-TIME migration of the already published
 * `catalog_cards` into the moderation queue: every existing card becomes a
 * `pending` candidate for the curator to review again.
 *
 * The mapping is PURE and DETERMINISTIC, which is what makes the operation
 * idempotent: the document id is `sha256(canonicalUrl)`, `createdAt` is the
 * card's own `observedAt` (never the run's clock), and the synthetic
 * [submitterHash] is derived from the canonical URL — so re-running the
 * migration produces byte-identical documents instead of duplicates.
 *
 * A migrated card had no listener and no playback verdict, so `playedAt` is
 * honestly 0: the curator reviews it as new material, not as a verified
 * submission. A card without a title or a URL is SKIPPED (null), never
 * invented.
 */
object CatalogCardModerationMigration {

    /**
     * The synthetic submitter marker of a migrated card. It is a hash of a
     * fixed, non-personal string: no listener uid ever existed for these
     * cards, and the queue's shape requires a non-empty `submitterHash`.
     */
    const val SUBMITTER_PREFIX = "catalog-migration:"

    /** The real card fields the queue consumes (the rest stay on the card). */
    private val CONSUMED = setOf(
        "sourceId", "sourceUrl", "title", "author", "narrator",
        "coverUrl", "durationSeconds", "chapterCount", "observedAt"
    )

    fun submitterHashFor(canonicalUrl: String): String = sha256(SUBMITTER_PREFIX + canonicalUrl.trim())

    /**
     * @param card one `catalog_cards` document, exactly as stored.
     * @return the pending candidate, or null when the card carries no usable
     *   identity (title + URL) — nothing is fabricated.
     */
    fun candidateFromCard(card: Map<String, Any?>): SubmissionCandidate? {
        val url = (card["sourceUrl"] as? String)?.trim().orEmpty()
        val title = (card["title"] as? String)?.trim().orEmpty()
        if (url.isEmpty() || title.isEmpty()) return null
        val canonical = SubmissionUrlCanonicalizer.canonicalOrSelf(url)
        return SubmissionCandidate(
            url = url,
            canonicalUrl = canonical,
            title = title,
            author = (card["author"] as? String)?.trim()?.takeIf { it.isNotEmpty() },
            narrator = (card["narrator"] as? String)?.trim()?.takeIf { it.isNotEmpty() },
            coverUrl = (card["coverUrl"] as? String)?.trim()?.takeIf { it.isNotEmpty() },
            uploader = null,
            durationSeconds = (card["durationSeconds"] as? Number)?.toLong()?.takeIf { it > 0 },
            chaptersCount = ((card["chapterCount"] as? Number)?.toInt() ?: 0).coerceAtLeast(0),
            sourceId = (card["sourceId"] as? String).orEmpty(),
            metadataJson = null,
            submitterHash = submitterHashFor(canonical),
            // No playback verdict ever fired for a card observed before
            // moderation existed — saying otherwise would be a lie.
            playedAt = 0L,
            // The card's OWN observation moment: deterministic across runs.
            createdAt = (card["observedAt"] as? Number)?.toLong() ?: 0L,
            state = SubmissionCandidate.State.PENDING
        )
    }

    /** True when this card yields a candidate (its identity is usable). */
    fun isMigratable(card: Map<String, Any?>): Boolean = candidateFromCard(card) != null

    /** The card fields this migration reads — the runbook's audit list. */
    fun consumedCardFields(): Set<String> = CONSUMED

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
}
