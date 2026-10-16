package com.slukhayka.audiobooks.data.recommend

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.sqrt

/** Host-only verified journal: one inference per Work, including across an interrupted run. */
object RecommendationEvalVectorCache {
    fun loadOrCompute(
        directory: File,
        backendIdentity: String,
        candidates: List<RecommendationEngine.Candidate>,
        dimension: Int,
        embedder: TextEmbedder
    ): Map<String, FloatArray> {
        require(dimension > 0 && backendIdentity.isNotBlank())
        val ordered = candidates.sortedBy { it.id }
        val byId = ordered.associateBy { it.id }
        require(byId.size == ordered.size)
        val context = MessageDigest.getInstance("SHA-256")
        fun field(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            context.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
            context.update(bytes)
        }
        field(backendIdentity)
        field(dimension.toString())
        for (candidate in ordered) { field(candidate.id); field(candidate.text) }
        val key = context.digest().joinToString("") { "%02x".format(it) }
        directory.mkdirs()
        val file = File(directory, "$key.vectors")
        return RandomAccessFile(file, "rw").use { journal ->
            val lock = journal.channel.tryLock() ?: error("Another evaluator owns this vector cache")
            lock.use {
                val result = linkedMapOf<String, FloatArray>()
                if (journal.length() == 0L) {
                    journal.writeUTF(MAGIC)
                    journal.writeUTF(key)
                    journal.writeInt(dimension)
                    journal.fd.sync()
                } else {
                    require(journal.readUTF() == MAGIC && journal.readUTF() == key && journal.readInt() == dimension) {
                        "Vector cache does not match frozen model, text or dimension"
                    }
                    var lastGood = journal.filePointer
                    while (journal.filePointer < journal.length()) {
                        try {
                            val id = journal.readUTF()
                            val vector = FloatArray(dimension) { journal.readFloat() }
                            val checksum = ByteArray(32).also { journal.readFully(it) }
                            require(id in byId && id !in result) { "Invalid or repeated cached Work" }
                            require(MessageDigest.isEqual(checksum, checksum(id, vector))) { "Corrupt cached vector: $id" }
                            validate(vector, dimension, id)
                            result[id] = vector
                            lastGood = journal.filePointer
                        } catch (_: EOFException) {
                            // Only an unfinished tail may be discarded. A bad
                            // checksum or valid but wrong context never is.
                            journal.setLength(lastGood)
                            journal.seek(lastGood)
                            break
                        }
                    }
                }
                println("verified vector cache: ${result.size}/${ordered.size}, backend=$backendIdentity")
                journal.seek(journal.length())
                val started = System.nanoTime()
                var fresh = 0
                for (candidate in ordered) {
                    if (candidate.id in result) continue
                    val vector = embedder.embed(candidate.text)
                    validate(vector, dimension, candidate.id)
                    journal.writeUTF(candidate.id)
                    vector.forEach { journal.writeFloat(it) }
                    journal.write(checksum(candidate.id, vector))
                    result[candidate.id] = vector
                    fresh++
                    if (fresh % 100 == 0 || result.size == ordered.size) {
                        journal.fd.sync()
                        val seconds = (System.nanoTime() - started) / 1_000_000_000.0
                        println("vectors: ${result.size}/${ordered.size}; fresh=$fresh; elapsed=%.1fs".format(java.util.Locale.ROOT, seconds))
                    }
                }
                journal.fd.sync()
                result
            }
        }
    }

    private fun validate(vector: FloatArray, dimension: Int, id: String) {
        require(vector.size == dimension && vector.all { it.isFinite() }) { "Invalid backend vector: $id" }
        val norm = sqrt(vector.sumOf { it.toDouble() * it })
        require(norm in .999..1.001) { "Degenerate or nonnormalized backend vector: $id (norm=$norm)" }
    }

    private fun checksum(id: String, vector: FloatArray): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeUTF(id)
            vector.forEach { output.writeFloat(it) }
        }
        return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
    }

    private const val MAGIC = "slukhayka-eval-vectors-v1"
}
