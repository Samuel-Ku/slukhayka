package com.slukhayka.audiobooks.data.imports

/** The same physical grouping is used by the preview and folder rescans. */
internal object LocalImportGrouping {
    const val ROOT_BOOK = "root-folder"

    fun key(fileName: String, parentFolder: String?, grouping: LocalFolderGrouping): String =
        if (!parentFolder.isNullOrBlank()) "folder:$parentFolder"
        else if (grouping == LocalFolderGrouping.ONE_BOOK) ROOT_BOOK else "root:$fileName"

    fun fileKey(fileName: String, parentFolder: String?): String =
        "${parentFolder.orEmpty()}/$fileName"

    fun group(entries: List<LocalAudioEntry>, grouping: LocalFolderGrouping): Map<String, List<LocalAudioEntry>> =
        entries.sortedWith { a, b -> compareNatural(fileKey(a.fileName, a.parentFolder), fileKey(b.fileName, b.parentFolder)) }
            .groupBy { key(it.fileName, it.parentFolder, grouping) }

    fun compareNatural(a: String, b: String): Int {
        val chunksA = CHUNKS.findAll(a.lowercase()).map { it.value }.toList()
        val chunksB = CHUNKS.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(chunksA.size, chunksB.size)) {
            val ca = chunksA[i]
            val cb = chunksB[i]
            val cmp = if (ca.first().isDigit() && cb.first().isDigit()) {
                val na = ca.trimStart('0').ifEmpty { "0" }
                val nb = cb.trimStart('0').ifEmpty { "0" }
                na.length.compareTo(nb.length).takeIf { it != 0 } ?: na.compareTo(nb)
            } else ca.compareTo(cb)
            if (cmp != 0) return cmp
        }
        return (chunksA.size - chunksB.size).takeIf { it != 0 } ?: a.compareTo(b)
    }
    private val CHUNKS = Regex("\\d+|\\D+")
}
