package com.example.data

/**
 * ADR-0007 — the deterministic domain-Edition id.
 *
 * `hash(mergeKey|language)` (or `hash(bookId|language)` when the mergeKey is
 * blank): stable across processes and re-imports, so the v13→v14 migration's
 * backfill and every future write agree on the SAME edition id for the same
 * book. Pure JVM so tests and the migration share one implementation.
 */
object EditionId {

    /**
     * The edition id of a book's rendition. [mergeKey] is the Work-level
     * identity (already includes the narrator); a blank key (legacy rows,
     * local imports) falls back to the unique [bookId].
     */
    fun forBook(mergeKey: String, bookId: String, language: String = ""): String =
        sha256Hex("${mergeKey.ifBlank { bookId }}\u0000$language".toByteArray(Charsets.UTF_8)).take(24)
}
