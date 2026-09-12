package com.slukhayka.audiobooks.data.catalog

import java.io.File

/** #526 — the validator a Source returned for one sitemap URL. */
data class SitemapValidator(
    val etag: String?,
    val lastModified: String?
)

/**
 * #526 — the persisted validators of the sitemap lane, so a restart AFTER the
 * TTL still sends `If-None-Match`/`If-Modified-Since` and a 304 can extend the
 * index's life without re-downloading (or re-parsing) one byte. A plain TSV,
 * best-effort: a missing or unreadable file is an empty map, never a crash.
 */
class SitemapValidatorStore(private val file: File) {

    fun load(): Map<String, SitemapValidator> {
        if (!file.exists()) return emptyMap()
        return runCatching {
            file.readLines()
                .mapNotNull { line ->
                    val parts = line.split('\t')
                    if (parts.size < 3 || parts[0].isBlank()) return@mapNotNull null
                    parts[0] to SitemapValidator(
                        etag = parts[1].takeIf { it.isNotBlank() },
                        lastModified = parts[2].takeIf { it.isNotBlank() }
                    )
                }
                .toMap()
        }.getOrDefault(emptyMap())
    }

    fun save(validators: Map<String, SitemapValidator>) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(
                validators.entries.joinToString("\n") { (url, validator) ->
                    listOf(url, validator.etag.orEmpty(), validator.lastModified.orEmpty()).joinToString("\t")
                }
            )
        }
    }
}
