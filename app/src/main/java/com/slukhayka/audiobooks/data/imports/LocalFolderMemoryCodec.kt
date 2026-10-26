package com.slukhayka.audiobooks.data.imports

import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.encodeUtf8

/**
 * The stored shape of one folder lineage (ADR-0057 «Пам'ять походження»):
 * pipe-separated fields in a fixed order, versioned.
 *
 * Version 2 carried `relative path → content hashes`. Version 3 adds the SAF
 * metadata (size, last-modified) per path, so a re-scan can reuse a hash
 * instead of re-reading an unchanged file (ADR-0061 §3). Decoding stays
 * backward-compatible: a v2 record becomes metadata-unknown — never a guess.
 */
internal object LocalFolderMemoryCodec {

    private const val VERSION_V2 = "2"
    private const val VERSION_V3 = "3"
    private val HASH = Regex("[0-9a-f]{64}")
    private val URL_SAFE_TOKEN = Regex("[A-Za-z0-9_-]*={0,2}")

    fun encode(lineage: LocalFolderLineage): String = listOf(
        VERSION_V3,
        token(lineage.bookId),
        lineage.grouping.name,
        lineage.groups.sorted().joinToString(".", transform = ::token),
        lineage.files.toSortedMap().entries.joinToString(".") { (path, record) ->
            token(path) + ":" + record.hashes.sorted().joinToString(",") +
                ";" + (record.sizeBytes?.toString().orEmpty()) +
                ";" + (record.lastModifiedMs?.toString().orEmpty())
        }
    ).joinToString("|")

    fun decode(value: String): LocalFolderLineage? = runCatching {
        val fields = value.split('|')
        require(fields.size == 5)
        val version = fields[0]
        require(version == VERSION_V2 || version == VERSION_V3)
        val bookId = untoken(fields[1])
        require(bookId.isNotBlank())
        LocalFolderLineage(
            bookId = bookId,
            grouping = LocalFolderGrouping.valueOf(fields[2]),
            groups = fields[3].split('.').filter { it.isNotEmpty() }.map(::untoken).toSet(),
            files = fields[4].split('.').filter { it.isNotEmpty() }.associate { entry ->
                val parts = entry.split(':', limit = 2)
                require(parts.size == 2)
                val meta = parts[1].split(';')
                require(meta.size == if (version == VERSION_V3) 3 else 1)
                val hashes = meta[0].split(',').filter { it.isNotEmpty() }.toSet()
                require(hashes.all { it.matches(HASH) })
                untoken(parts[0]) to LocalFileRecord(
                    hashes = hashes,
                    sizeBytes = if (version == VERSION_V3) meta[1].toLongOrNull() else null,
                    lastModifiedMs = if (version == VERSION_V3) meta[2].toLongOrNull() else null
                )
            }
        )
    }.getOrNull()

    private fun token(value: String): String = value.encodeUtf8().base64Url().trimEnd('=')

    private fun untoken(value: String): String {
        require(value.matches(URL_SAFE_TOKEN))
        return requireNotNull(value.decodeBase64()).utf8()
    }
}

/**
 * ADR-0061 §3 — may a re-scan reuse a remembered hash instead of reading the
 * file? Only when the path is remembered, its (size, last-modified) still
 * equals the scan, and the path carries exactly ONE remembered hash. Several
 * hashes for one path mean several owners or changed bytes — an ambiguity,
 * not a cache hit.
 */
internal fun rememberedHashFor(
    records: Map<String, LocalFileRecord>,
    fileName: String,
    parentFolder: String?,
    sizeBytes: Long?,
    lastModifiedMs: Long?
): String? {
    val record = records[LocalImportGrouping.fileKey(fileName, parentFolder)] ?: return null
    if (!record.sameMetadata(sizeBytes, lastModifiedMs)) return null
    return record.hashes.singleOrNull()
}

/**
 * Folds one fresh observation into what the memory already knew about that
 * path. Hashes accumulate (several owners may share a path); the SAF metadata
 * comes from the NEW observation and never falls back to an older one: a
 * provider that stops reporting it turns the metadata unknown, so the next
 * re-scan reads the file instead of trusting a stale timestamp.
 */
internal fun observedRecord(previous: LocalFileRecord?, file: FolderRescan.RescanFile): LocalFileRecord =
    LocalFileRecord(
        hashes = previous?.hashes.orEmpty() + file.contentHash,
        sizeBytes = file.sizeBytes,
        lastModifiedMs = file.lastModifiedMs
    )

/**
 * One record per relative path across every lineage of a tree. Records that
 * disagree on metadata collapse to "unknown" rather than a silent choice:
 * the caller then reads the file, which is always safe.
 */
internal fun mergedRecords(lineages: List<LocalFolderLineage>): Map<String, LocalFileRecord> {
    val merged = mutableMapOf<String, LocalFileRecord>()
    for (lineage in lineages) {
        for ((path, record) in lineage.files) {
            val previous = merged[path]
            merged[path] = if (previous == null) {
                record
            } else {
                LocalFileRecord(
                    hashes = previous.hashes + record.hashes,
                    sizeBytes = agree(previous.sizeBytes, record.sizeBytes),
                    lastModifiedMs = agree(previous.lastModifiedMs, record.lastModifiedMs)
                )
            }
        }
    }
    return merged
}

private fun agree(a: Long?, b: Long?): Long? = if (a != null && a == b) a else null
