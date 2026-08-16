package com.slukhayka.audiobooks.data

/**
 * ADR-0007 + ADR-0010 — the deterministic domain-Edition id.
 *
 * `hash(mergeKey|narrator|language)` (or `hash(bookId|narrator|language)`
 * when the mergeKey is blank): stable across processes and re-imports, so the
 * v16 migration's remap and every future write agree on the SAME edition id
 * for the same rendition. The narrator is part of the id because the Work key
 * no longer carries it (ADR-0010): two narrations of one Work must get
 * distinct Edition ids, or they would share listening state (ADR-0001 —
 * incompatible narrations never share timestamps). Pure JVM so tests and the
 * migration share one implementation.
 */
object EditionId {

    /**
     * The edition id of a book's rendition. [mergeKey] is the Work-level
     * identity (title|author — narrator excluded, ADR-0010); [narrator] is
     * the rendition's narrator; a blank key (legacy rows, local imports)
     * falls back to the unique [bookId]. Two narrations of the same Work
     * differ in [narrator] and therefore in id.
     */
    fun forBook(mergeKey: String, bookId: String, narrator: String = "", language: String = ""): String =
        sha256Hex("${mergeKey.ifBlank { bookId }}\u0000$narrator\u0000$language".toByteArray(Charsets.UTF_8)).take(24)

    /**
     * The v13→v14-era formula — narrator inside the mergeKey, no separate
     * narrator input: `hash(mergeKey|language)`. Used ONLY by that historical
     * migration (and its test); the v16 migration remaps every edition id to
     * the ADR-0010 formula above. Keeping it lets the historical migration
     * stay byte-for-byte what it shipped as.
     */
    fun forBookLegacy(mergeKey: String, bookId: String, language: String = ""): String =
        sha256Hex("${mergeKey.ifBlank { bookId }}\u0000$language".toByteArray(Charsets.UTF_8)).take(24)
}
