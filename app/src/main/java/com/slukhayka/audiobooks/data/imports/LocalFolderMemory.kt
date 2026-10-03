package com.slukhayka.audiobooks.data.imports

import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.CorrectionEntity
import com.slukhayka.audiobooks.data.sha256Hex
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.encodeUtf8

/** Physical provenance, independent of the listener's editable book title. */
internal data class LocalFolderOrigin(
    val treeUri: String,
    val grouping: LocalFolderGrouping,
    val files: List<FolderRescan.RescanFile>,
    val claimedGroups: Set<String> = files.map { LocalImportGrouping.key(it.fileName, it.parentFolder, grouping) }.toSet()
)

internal data class LocalFolderLineage(
    val bookId: String,
    val grouping: LocalFolderGrouping,
    val groups: Set<String>,
    val files: Map<String, Set<String>>
) {
    val hashes: Set<String> get() = files.values.flatten().toSet()
}


/** Lives in correction memory; callers write it inside the Edition's Room transaction. */
internal class LocalFolderMemory(private val dao: AudiobookDao) {
    suspend fun read(treeUri: String): List<LocalFolderLineage> =
        dao.getCorrectionsForMergeKey(key(treeUri)).mapNotNull { decode(it.value) }

    suspend fun remember(bookId: String, origin: LocalFolderOrigin) {
        val mergeKey = key(origin.treeUri)
        val rows = dao.getCorrectionsForMergeKey(mergeKey).filter { it.kind == "FIELD" }
        val previous = rows.mapNotNull { decode(it.value) }.firstOrNull { it.bookId == bookId }
        val files = previous?.files.orEmpty().toMutableMap()
        origin.files.forEach { file ->
            val path = LocalImportGrouping.fileKey(file.fileName, file.parentFolder)
            files[path] = files[path].orEmpty() + file.contentHash
        }
        val lineage = LocalFolderLineage(
            bookId, origin.grouping,
            previous?.groups.orEmpty() + origin.claimedGroups,
            files
        )
        // Preserve other owners' timestamps and opaque records. Replacing
        // only this owner's snapshot prevents stale compound-PK duplicates.
        dao.deleteCorrection(mergeKey, "FIELD")
        rows.filter { decode(it.value)?.bookId != bookId }.forEach { dao.upsertCorrection(it) }
        dao.upsertCorrection(CorrectionEntity(mergeKey = mergeKey, kind = "FIELD", value = encode(lineage), origin = "USER_MADE"))
    }

    /** Same transaction as the Source re-anchor and removal of the sibling. */
    suspend fun reparent(fromBookId: String, toBookId: String) {
        for ((mergeKey, rows) in dao.getLocalFolderCorrections().groupBy { it.mergeKey }) {
            val decoded = rows.mapNotNull { decode(it.value) }
            if (decoded.none { it.bookId == fromBookId }) continue
            val owners = decoded.filter { it.bookId == fromBookId || it.bookId == toBookId }
            val files = mutableMapOf<String, Set<String>>()
            owners.forEach { owner -> owner.files.forEach { (path, hashes) ->
                files[path] = files[path].orEmpty() + hashes
            } }
            val survivor = owners.first().copy(
                bookId = toBookId,
                groups = owners.flatMap { it.groups }.toSet(),
                files = files
            )
            dao.deleteCorrection(mergeKey, "FIELD")
            rows.filter { decode(it.value)?.bookId !in setOf(fromBookId, toBookId) }
                .forEach { dao.upsertCorrection(it) }
            dao.upsertCorrection(CorrectionEntity(
                mergeKey = mergeKey, kind = "FIELD", value = encode(survivor), origin = "USER_MADE"
            ))
        }
    }

    private fun key(treeUri: String) = "local-folder:${sha256Hex(treeUri.toByteArray(Charsets.UTF_8))}"
    private fun token(value: String): String = value.encodeUtf8().base64Url().trimEnd('=')
    private fun untoken(value: String): String {
        require(value.matches(URL_SAFE_TOKEN))
        return requireNotNull(value.decodeBase64()).utf8()
    }
    private val URL_SAFE_TOKEN = Regex("[A-Za-z0-9_-]*={0,2}")
    private fun encode(lineage: LocalFolderLineage): String = listOf(
        "2", token(lineage.bookId), lineage.grouping.name,
        lineage.groups.sorted().joinToString(".", transform = ::token),
        lineage.files.toSortedMap().entries.joinToString(".") { (path, hashes) -> token(path) + ":" + hashes.sorted().joinToString(",") }
    ).joinToString("|")

    private fun decode(value: String): LocalFolderLineage? = runCatching {
        val fields = value.split('|')
        require(fields.size == 5 && fields[0] == "2")
        val id = untoken(fields[1])
        require(id.isNotBlank())
        LocalFolderLineage(id, LocalFolderGrouping.valueOf(fields[2]),
            fields[3].split('.').filter { it.isNotEmpty() }.map(::untoken).toSet(),
            fields[4].split('.').filter { it.isNotEmpty() }.associate { file ->
                val parts = file.split(':', limit = 2)
                require(parts.size == 2)
                val hashes = parts[1].split(',').toSet()
                require(hashes.all { it.matches(Regex("[0-9a-f]{64}")) })
                untoken(parts[0]) to hashes
            })
    }.getOrNull()
}
