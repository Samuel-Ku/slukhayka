package com.slukhayka.audiobooks.data.catalog

import java.io.File

/** A persisted Work index with the moment it was built. */
data class PersistedWorkIndex(
    val entries: List<CatalogIndexEntry>,
    val refreshedAtMs: Long
)

/**
 * Spec-49 follow-up (2026-09-10) — the file-backed carrier of the Work
 * index: a refreshed sitemap/card enumeration survives restarts, so a launch
 * inside the TTL serves the lookup without touching a single sitemap. A
 * TSV keeps it dependency-free (no schema migration) and human-readable in
 * `adb shell run-as` debugging: a header line then `sourceId, url, slug,
 * mergeKey` rows.
 *
 * Best-effort by contract: a missing, corrupt or unreadable file is a miss;
 * a failing write contributes nothing.
 */
class WorkIndexStore(private val file: File) {

    fun load(): PersistedWorkIndex? {
        if (!file.isFile) return null
        return try {
            val lines = file.readLines()
            if (lines.size < 2 || !lines.first().startsWith(HEADER)) return null
            val refreshedAtMs = lines.first().removePrefix(HEADER).trim().toLongOrNull() ?: return null
            val entries = lines.drop(1).mapNotNull { line -> decode(line) }
            if (entries.isEmpty()) null else PersistedWorkIndex(entries, refreshedAtMs)
        } catch (_: Exception) {
            null
        }
    }

    fun save(index: PersistedWorkIndex) {
        try {
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(
                buildString {
                    append(HEADER).append(index.refreshedAtMs).append('\n')
                    for (entry in index.entries) {
                        append(entry.sourceId).append('\t')
                            .append(entry.url).append('\t')
                            .append(entry.slug).append('\t')
                            .append(entry.mergeKey).append('\n')
                    }
                }
            )
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
        } catch (_: Exception) {
            // Best-effort: the in-memory index still serves this session.
        }
    }

    private fun decode(line: String): CatalogIndexEntry? {
        val parts = line.split('\t')
        if (parts.size < 4) return null
        val sourceId = parts[0].trim()
        val url = parts[1].trim()
        if (sourceId.isBlank() || url.isBlank()) return null
        return CatalogIndexEntry(
            sourceId = sourceId,
            url = url,
            slug = parts[2].trim(),
            mergeKey = parts[3].trim()
        )
    }

    private companion object {
        const val HEADER = "slukhayka-work-index-v1\t"
    }
}
