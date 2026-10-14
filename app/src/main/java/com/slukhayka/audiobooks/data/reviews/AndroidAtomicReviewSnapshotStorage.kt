package com.slukhayka.audiobooks.data.reviews

import android.content.Context
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Confirmed public bytes only. All operations are serialized by the policy's shared coordinator. */
class AndroidAtomicReviewSnapshotStorage(context: Context, namespace: ReviewSnapshotNamespace) : ReviewSnapshotStorage {
    private val namespaceKey = namespaceKey(namespace)
    private val directory = runCatching { File(requireNotNull(context.noBackupFilesDir), "review-confirmed-v1/$namespaceKey") }.getOrNull()
    private val canonicalDirectory = runCatching { directory?.canonicalFile }.getOrNull()
    override val scopeId = canonicalDirectory?.path ?: "unavailable-review-confirmed:$namespaceKey"

    private fun readyDirectory(): File? = canonicalDirectory?.takeIf {
        (it.exists() && it.isDirectory) || it.mkdirs()
    }

    private fun atomic(key: String): AtomicFile? {
        require(key.matches(Regex("[a-f0-9]{64}")))
        return readyDirectory()?.let { AtomicFile(File(it, "$key.bin")) }
    }

    override fun read(key: String): ReviewSnapshotBytes {
        return try {
            val file = atomic(key) ?: return ReviewSnapshotBytes.Failure
            val base = file.baseFile
            // Legacy backups contain confirmed truth even when the base was interrupted or lost.
            if (!base.exists() && !File(base.path + ".bak").exists()) return ReviewSnapshotBytes.Missing
            file.openRead().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_BYTES) return ReviewSnapshotBytes.Failure
                    output.write(buffer, 0, count)
                }
                ReviewSnapshotBytes.Data(output.toByteArray())
            }
        } catch (_: Exception) { ReviewSnapshotBytes.Failure }
    }

    override fun write(key: String, bytes: ByteArray): Boolean {
        if (bytes.size > MAX_BYTES) return false
        val file = try { atomic(key) } catch (_: Exception) { null } ?: return false
        var stream: FileOutputStream? = null
        return try {
            val opened = file.startWrite()
            stream = opened
            opened.write(bytes)
            opened.flush()
            opened.fd.sync()
            file.finishWrite(opened)
            stream = null
            // AtomicFile's finishWrite can log failures rather than throw; verify its committed bytes.
            val committed = read(key) as? ReviewSnapshotBytes.Data
            committed != null && committed.bytes.contentEquals(bytes)
        } catch (_: Exception) {
            stream?.let { runCatching { file.failWrite(it) } }
            false
        }
    }

    override fun keys(): List<String>? {
        return try {
            val directory = readyDirectory() ?: return null
            val files = directory.listFiles() ?: return null
            files.mapNotNull { file ->
                val match = Regex("([a-f0-9]{64})\\.bin(?:\\.bak)?").matchEntire(file.name)
                if (file.isFile) match?.groupValues?.get(1) else null
            }.distinct()
        } catch (_: Exception) { null }
    }

    companion object {
        private const val MAX_BYTES = 4 * 1024 * 1024
        private fun namespaceKey(namespace: ReviewSnapshotNamespace): String {
            require(namespace.databaseId == "(default)")
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { output ->
                for (value in listOf(namespace.projectId, namespace.firebaseAppName, namespace.applicationId, namespace.databaseId)) {
                    val encoded = value.toByteArray(Charsets.UTF_8)
                    require(value.isNotBlank() && encoded.size <= 65_536 && encoded.toString(Charsets.UTF_8) == value)
                    output.writeInt(encoded.size)
                    output.write(encoded)
                }
            }
            return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }
}
