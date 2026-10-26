package com.slukhayka.audiobooks.data.imports

import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.CorrectionEntity
import com.slukhayka.audiobooks.data.sha256Hex

/** Physical provenance, independent of the listener's editable book title. */
internal data class LocalFolderOrigin(
    val treeUri: String,
    val grouping: LocalFolderGrouping,
    val files: List<FolderRescan.RescanFile>,
    val claimedGroups: Set<String> = files.map { LocalImportGrouping.key(it.fileName, it.parentFolder, grouping) }.toSet()
)

/**
 * What the folder memory knows about ONE relative path (ADR-0057/0061): the
 * content hashes observed there and the SAF metadata at the last scan.
 * [sizeBytes]/[lastModifiedMs] are null for records written before the
 * metadata format (v2) and when a provider reports neither — "unknown", never
 * a fabricated number.
 */
internal data class LocalFileRecord(
    val hashes: Set<String>,
    val sizeBytes: Long? = null,
    val lastModifiedMs: Long? = null
) {
    /**
     * ADR-0061 §3 — metadata-equal only when BOTH observations are known and
     * equal. An unknown side is not a match: the re-scan must read the file.
     */
    fun sameMetadata(sizeBytes: Long?, lastModifiedMs: Long?): Boolean =
        this.sizeBytes != null && lastModifiedMs != null &&
            this.sizeBytes == sizeBytes && this.lastModifiedMs == lastModifiedMs
}

internal data class LocalFolderLineage(
    val bookId: String,
    val grouping: LocalFolderGrouping,
    val groups: Set<String>,
    val files: Map<String, LocalFileRecord>
) {
    val hashes: Set<String> get() = files.values.flatMapTo(mutableSetOf()) { it.hashes }
}

/** Lives in correction memory; callers write it inside the Edition's Room transaction. */
internal class LocalFolderMemory(private val dao: AudiobookDao) {
    suspend fun read(treeUri: String): List<LocalFolderLineage> =
        dao.getCorrectionsForMergeKey(key(treeUri)).mapNotNull { LocalFolderMemoryCodec.decode(it.value) }

    suspend fun remember(bookId: String, origin: LocalFolderOrigin) {
        val mergeKey = key(origin.treeUri)
        val rows = dao.getCorrectionsForMergeKey(mergeKey).filter { it.kind == "FIELD" }
        val previous = rows.mapNotNull { LocalFolderMemoryCodec.decode(it.value) }.firstOrNull { it.bookId == bookId }
        val files = previous?.files.orEmpty().toMutableMap()
        origin.files.forEach { file ->
            val path = LocalImportGrouping.fileKey(file.fileName, file.parentFolder)
            val previousRecord = files[path]
            // The hash set keeps every observed owner of this path; the SAF
            // metadata describes the NEWEST observation (the scan that just ran).
            files[path] = LocalFileRecord(
                hashes = previousRecord?.hashes.orEmpty() + file.contentHash,
                sizeBytes = file.sizeBytes ?: previousRecord?.sizeBytes,
                lastModifiedMs = file.lastModifiedMs ?: previousRecord?.lastModifiedMs
            )
        }
        val lineage = LocalFolderLineage(
            bookId, origin.grouping,
            previous?.groups.orEmpty() + origin.claimedGroups,
            files
        )
        // Preserve other owners' timestamps and opaque records. Replacing
        // only this owner's snapshot prevents stale compound-PK duplicates.
        dao.deleteCorrection(mergeKey, "FIELD")
        rows.filter { LocalFolderMemoryCodec.decode(it.value)?.bookId != bookId }.forEach { dao.upsertCorrection(it) }
        dao.upsertCorrection(
            CorrectionEntity(
                mergeKey = mergeKey,
                kind = "FIELD",
                value = LocalFolderMemoryCodec.encode(lineage),
                origin = "USER_MADE"
            )
        )
    }

    /** Same transaction as the Source re-anchor and removal of the sibling. */
    suspend fun reparent(fromBookId: String, toBookId: String) {
        for ((mergeKey, rows) in dao.getLocalFolderCorrections().groupBy { it.mergeKey }) {
            val decoded = rows.mapNotNull { LocalFolderMemoryCodec.decode(it.value) }
            if (decoded.none { it.bookId == fromBookId }) continue
            val owners = decoded.filter { it.bookId == fromBookId || it.bookId == toBookId }
            val files = mutableMapOf<String, LocalFileRecord>()
            owners.forEach { owner ->
                owner.files.forEach { (path, record) ->
                    val previous = files[path]
                    files[path] = if (previous == null) {
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
            val survivor = owners.first().copy(
                bookId = toBookId,
                groups = owners.flatMap { it.groups }.toSet(),
                files = files
            )
            dao.deleteCorrection(mergeKey, "FIELD")
            rows.filter { LocalFolderMemoryCodec.decode(it.value)?.bookId !in setOf(fromBookId, toBookId) }
                .forEach { dao.upsertCorrection(it) }
            dao.upsertCorrection(CorrectionEntity(
                mergeKey = mergeKey, kind = "FIELD", value = LocalFolderMemoryCodec.encode(survivor), origin = "USER_MADE"
            ))
        }
    }

    private fun agree(a: Long?, b: Long?): Long? = if (a != null && a == b) a else null

    private fun key(treeUri: String) = "local-folder:${sha256Hex(treeUri.toByteArray(Charsets.UTF_8))}"
}
