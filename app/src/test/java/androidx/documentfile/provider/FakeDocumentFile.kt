package androidx.documentfile.provider

import android.net.Uri

/**
 * In-memory [DocumentFile] tree used by [LocalFolderScannerTest]. Lives in the
 * `androidx.documentfile.provider` package because [DocumentFile]'s constructor
 * is package-private — this is the standard seam for walking a SAF tree without
 * a real DocumentsProvider. Streams are never opened: the scanner only reads
 * names, types and child lists.
 */
class FakeDocumentFile(
    private val displayName: String?,
    private val directory: Boolean,
    private val children: List<FakeDocumentFile> = emptyList()
) : DocumentFile(null) {

    private val documentUri = Uri.parse("content://fake/${displayName ?: "root"}")

    override fun getName(): String? = displayName
    override fun getUri(): Uri = documentUri
    override fun getType(): String? = if (directory) "vnd.android.document/directory" else "audio/mpeg"
    override fun isDirectory(): Boolean = directory
    override fun isFile(): Boolean = !directory
    override fun isVirtual(): Boolean = false
    override fun lastModified(): Long = 0L
    override fun length(): Long = if (directory) 0L else 42L
    override fun canRead(): Boolean = true
    override fun canWrite(): Boolean = false
    override fun delete(): Boolean = false
    override fun exists(): Boolean = true
    override fun listFiles(): Array<DocumentFile> = children.toTypedArray()
    override fun renameTo(displayName: String): Boolean = false
    override fun createFile(mimeType: String, displayName: String): DocumentFile? = null
    override fun createDirectory(displayName: String): DocumentFile? = null
}
