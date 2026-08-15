package com.example.data.collections

/**
 * Spec-16 T1 — strict JSON decoding for the curated collection assets
 * (pure JVM, no Android org.json stubs — the same convention the source
 * adapters follow; parsing itself lives in the shared [MiniJson]).
 *
 * The asset format is fixed and curated by the maintainer, so the decoder is
 * deliberately strict: it understands objects with string values, arrays of
 * objects and standard string escapes (including `\uXXXX`), and returns
 * `null` on anything else — a malformed asset never crashes the app, it
 * simply contributes no collection (best-effort load).
 *
 * Expected shape:
 * ```
 * { "id": "nobel", "name": "Нобелівські лауреати",
 *   "sourceNote": "…", "entries": [
 *     { "author": "…", "title": "…", "note": "…" },
 *     { "author": "…" }
 *   ] }
 * ```
 */
object CollectionJson {

    /** Decodes one asset text into a [CollectionList], or `null` when the
     *  text is not a valid collection object (or lacks id/name). */
    fun decode(text: String): CollectionList? {
        return try {
            val obj = MiniJson.parse(text) as? Map<*, *> ?: return null
            val id = obj["id"] as? String ?: return null
            val name = obj["name"] as? String ?: return null
            if (id.isBlank() || name.isBlank()) return null
            val sourceNote = obj["sourceNote"] as? String ?: ""
            val entries = when (val raw = obj["entries"]) {
                null -> emptyList()
                is List<*> -> raw.map { entry ->
                    // Strict: any malformed entry invalidates the whole
                    // collection — a curated asset either parses fully or is
                    // absent, so a release review catches data bugs.
                    val map = entry as? Map<*, *> ?: return null
                    val author = map["author"] as? String ?: return null
                    if (author.isBlank()) return null
                    CollectionEntry(
                        author = author,
                        title = map["title"] as? String,
                        note = map["note"] as? String
                    )
                }
                else -> return null
            }
            CollectionList(id, name, sourceNote, entries)
        } catch (e: Exception) {
            null
        }
    }
}
