package com.slukhayka.audiobooks.data.ingest

/**
 * Spec-53 T8 — the pure key of one deferred link. Deterministic on purpose:
 * the same canonical URL pasted offline twice is ONE queue entry, and a link
 * that was already processed can never come back under a fresh key. It is
 * only ever compared within the local queue, never published.
 */
object DeferredSubmissionQueue {
    fun keyFor(canonicalUrl: String): String =
        "deferred-" + Integer.toHexString(canonicalUrl.trim().hashCode())
}
