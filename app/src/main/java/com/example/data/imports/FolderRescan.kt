package com.example.data.imports

import com.example.data.db.ChapterEntity

/**
 * The pure re-scan diff core (wayfinder #42): classifies the live scan of a
 * previously imported folder against the library's stored chapters by
 * content hash — the only identity. Names and paths are never identity; a
 * same-name file with different bytes is a different file.
 *
 * The diff answers exactly the four buckets the re-scan report needs:
 * new files (to add as chapters/books), missing chapters (files gone from
 * the source tree — the library entry and its private copy stay untouched,
 * wayfinder #59), moved files (same bytes, different name — the private copy
 * is still valid, report only), duplicates (bytes already in another book —
 * never copied). Pure JVM, no Android, no I/O: the caller hashes the streams
 * and feeds [RescanFile]s.
 */
object FolderRescan {

    /** One file observed in the live scan, with its content hash. */
    data class RescanFile(
        val fileName: String,
        /** Same grouping key as the import: full relative folder path or null for root files. */
        val parentFolder: String?,
        val contentHash: String
    )

    /** What a scanned file means for one book of the tree. */
    enum class Bucket { NEW, MISSING, MOVED, DUPLICATE, UNCHANGED }

    /** The per-book verdict of a re-scan. */
    data class RescanDiff(
        /** Files with hashes unknown to the library — become new chapters. */
        val newFiles: List<RescanFile>,
        /** Chapters whose hash is absent from the live tree — report only. */
        val missingChapters: List<ChapterEntity>,
        /** Files whose bytes exist in THIS book but under a different name — report only. */
        val movedFiles: List<RescanFile>,
        /** Files whose bytes already live in ANOTHER library entry — skip, never copy. */
        val duplicateFiles: List<RescanFile>,
        /** Files that match a chapter of this book exactly — nothing to do. */
        val unchangedFiles: List<RescanFile>
    ) {
        val changed: Boolean
            get() = newFiles.isNotEmpty() || missingChapters.isNotEmpty() ||
                movedFiles.isNotEmpty() || duplicateFiles.isNotEmpty()
    }

    /**
     * Classifies one book's chapters against the whole live scan. The
     * library-wide hash set decides duplicates; this book's own hashes decide
     * new vs moved vs unchanged. Missing = stored hash not seen anywhere in
     * the tree (the file was deleted or the folder moved).
     */
    fun computeDiff(
        chapters: List<ChapterEntity>,
        libraryHashSet: Set<String>,
        scanned: List<RescanFile>
    ): RescanDiff {
        val ownHashes = chapters.mapNotNull { it.contentHash }.toSet()
        val scannedHashes = scanned.map { it.contentHash }.toSet()

        val newFiles = mutableListOf<RescanFile>()
        val movedFiles = mutableListOf<RescanFile>()
        val duplicateFiles = mutableListOf<RescanFile>()
        val unchangedFiles = mutableListOf<RescanFile>()

        // A chapter title is the file stem without the extension, and the
        // stored hash is the same bytes — so a hash match with a different
        // name is a rename (moved), not a new file.
        val titleByHash = chapters
            .filter { it.contentHash != null }
            .associate { it.contentHash!! to it.title }
        val chapterStem = { name: String -> name.substringBeforeLast('.').trim().lowercase() }

        for (file in scanned) {
            when {
                // Byte-identical to a chapter of this book.
                file.contentHash in ownHashes -> {
                    val storedTitle = titleByHash[file.contentHash]
                    if (storedTitle != null && chapterStem(storedTitle) != chapterStem(file.fileName)) {
                        movedFiles.add(file)
                    } else {
                        unchangedFiles.add(file)
                    }
                }
                // Bytes already in the library under another book.
                file.contentHash in libraryHashSet -> duplicateFiles.add(file)
                else -> newFiles.add(file)
            }
        }

        val missingChapters = chapters.filter { ch ->
            ch.contentHash != null && ch.contentHash !in scannedHashes
        }

        return RescanDiff(
            newFiles = newFiles,
            missingChapters = missingChapters,
            movedFiles = movedFiles,
            duplicateFiles = duplicateFiles,
            unchangedFiles = unchangedFiles
        )
    }
}
