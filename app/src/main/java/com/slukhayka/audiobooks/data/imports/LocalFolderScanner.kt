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
    /**
     * The SAF document URI of the file itself (ADR-0061). When the folder is
     * the storage, this URI is the track's playable address — the import
     * writes it instead of copying bytes into app storage. Null only for
     * entries built without a document (legacy seams).
     */
    val uri: String? = null,
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
            return listOf(
                LocalAudioEntry(fileName = name, parentFolder = null, uri = uri.toString()) {
                    openOrFail(resolver, uri)
                }
            )
        }
        val result = mutableListOf<LocalAudioEntry>()
        collect(dir = root, relativePath = null, resolver = resolver, out = result)
        return result
    }

    /**
     * Opens a SAF document, turning a vanished file or a revoked grant into a
     * NAMED refusal instead of a bare NPE.
     *
     * #1186 — `openInputStream` returns null when the document is gone or the
     * permission has been revoked since the scan. `!!` turned that into
     * `NullPointerException` with no uri, no file, no cause: a crash the user
     * cannot act on and a bug report that cannot be diagnosed. The stream is
     * still non-null by contract, so the fix is not a nullable return — it is
     * refusing with a message that names the document.
     */
    // `Resolver.openInputStream` є `final`, а ShadowContentResolver цієї версії
    // не має `setInputStream`, тож гілку `null` інакше не покрити тестом
    // (#1374). Відкриття винесено в параметр: типове значення робить рівно те,
    // що було, а тест підставляє opener, який повертає null.
    internal fun openOrFail(
        resolver: ContentResolver,
        uri: Uri,
        open: (ContentResolver, Uri) -> InputStream? = { r, u -> r.openInputStream(u) },
    ): InputStream =
        open(resolver, uri)
            ?: throw IllegalStateException("SAF document is no longer readable: $uri")

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
                    out.add(
                        LocalAudioEntry(
                            fileName = name,
                            parentFolder = relativePath,
                            uri = uri.toString()
                        ) {
                            openOrFail(resolver, uri)
                        }
                    )
                }
            }
        }
    }
}
