package com.slukhayka.audiobooks.data.imports

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.InputStream

/**
 * One audio file discovered by [LocalFolderScanner] inside a picked SAF tree
 * (spec #8 Block 4). [openStream] is lazy so the grouping/import core in
 * `LibraryImport.importAudioEntries` stays pure JVM-testable — it never
 * touches a ContentResolver.
 */
data class LocalAudioEntry(
    val fileName: String,
    /**
     * Relative path from the picked root to the file's parent directory
     * (e.g. `SeriesA/Кобзар`), or null for files at the root itself. The full
     * path — not just the folder name — is used as the grouping key so two
     * same-named folders in different branches never merge into one book.
     */
    val parentFolder: String?,
    val openStream: () -> InputStream
)

/**
 * Recursively walks a SAF tree picked via `OpenDocumentTree` and collects the
 * playable audio files (mp3 / m4a / ogg, plus the common audiobook formats
 * m4b and aac). Sub-directories are traversed depth-first; every file keeps
 * its immediate parent folder name so the importer can group a folder of
 * chapter files into one multi-chapter book.
 */
object LocalFolderScanner {

    /** Extensions treated as playable audiobook audio. */
    private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "ogg", "m4b", "aac")

    fun scan(context: Context, treeUri: Uri): List<LocalAudioEntry> {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        return scan(root, context.contentResolver)
    }

    /** Test seam: walk any [DocumentFile] tree (real or fake) with a resolver. */
    fun scan(root: DocumentFile, resolver: ContentResolver): List<LocalAudioEntry> {
        if (!root.isDirectory) {
            // Defensive: a tree picker yields a directory, but the seam also
            // accepts a single file — import it as one root-level entry.
            val name = root.name ?: return emptyList()
            if (name.substringAfterLast('.', "").lowercase() !in AUDIO_EXTENSIONS) return emptyList()
            val uri = root.uri
            return listOf(LocalAudioEntry(fileName = name, parentFolder = null) {
                resolver.openInputStream(uri)!!
            })
        }
        val result = mutableListOf<LocalAudioEntry>()
        collect(dir = root, relativePath = null, resolver = resolver, out = result)
        return result
    }

    private fun collect(
        dir: DocumentFile,
        relativePath: String?,
        resolver: ContentResolver,
        out: MutableList<LocalAudioEntry>
    ) {
        for (child in dir.listFiles()) {
            val name = child.name ?: continue
            when {
                child.isDirectory -> {
                    val nextPath = if (relativePath.isNullOrBlank()) name else "$relativePath/$name"
                    collect(dir = child, relativePath = nextPath, resolver = resolver, out = out)
                }
                child.isFile && name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS -> {
                    val uri = child.uri
                    out.add(LocalAudioEntry(fileName = name, parentFolder = relativePath) {
                        resolver.openInputStream(uri)!!
                    })
                }
            }
        }
    }
}
