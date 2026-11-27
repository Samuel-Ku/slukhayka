package com.slukhayka.audiobooks.data.merge

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import org.json.JSONException
import org.json.JSONArray
import org.json.JSONObject

/** Book policy proof version is independent of immutable journal payload formats 1–4. */
internal object ManualWorkMergeBookProof {
    const val FORMAT = 3
    private const val MAX_BOOKS = 10_000
    private const val MAX_BYTES = 384 * 1024

    fun requireFormat(proof: JSONObject, payloadVersion: Int): Int = jsonFields {
        val format = proof.getInt("format")
        require(format == (if (payloadVersion >= 3) 2 else 1) ||
            (format == FORMAT && payloadVersion == 4)) { "Unknown approval proof format" }
        format
    }

    private fun validate(ids: List<String>): List<String> {
        require(ids.size <= MAX_BOOKS && ids == ids.distinct().sortedWith(::compareMergeUtf8))
        require(ids.all { it.isNotBlank() && Charsets.UTF_8.newEncoder().canEncode(it) })
        require(JSONArray(ids).toString().toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        return ids
    }

    fun approval(legacyApproval: String, books: List<String>): String = digest(
        "ManualWorkMergeApproval/BookProtection/v1", listOf(legacyApproval) + validate(books)
    )

    fun write(proof: JSONObject, books: List<String>): JSONObject {
        proof.put("format", FORMAT).put("protectedBooks", JSONArray(validate(books)))
        return proof.put("bookProtectionDigest", binding(proof, books))
    }

    fun read(proof: JSONObject): List<String> = jsonFields {
        require(proof.getInt("format") == FORMAT)
        val values = proof.getJSONArray("protectedBooks")
        require(values.length() <= MAX_BOOKS)
        val books = validate((0 until values.length()).map { index ->
            require(values.get(index) is String) { "Book policy identity must be text" }
            values.getString(index)
        })
        require(proof.getString("bookProtectionDigest") == binding(proof, books)) { "Book policy proof binding changed" }
        books
    }

    private inline fun <T> jsonFields(block: () -> T): T = try {
        block()
    } catch (invalid: JSONException) {
        throw IllegalArgumentException("Book policy proof fields are invalid", invalid)
    }

    private fun binding(proof: JSONObject, books: List<String>): String = digest(
        "ManualWorkMergeBookProtectionProof/v1",
        listOf("approval", "beforeDigest", "afterDigest", "afterFingerprint", "scopeDigest").map(proof::getString) + books
    )

    private fun digest(domain: String, fields: List<String>): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            fun text(value: String) {
                val encoded = value.toByteArray(Charsets.UTF_8)
                out.writeInt(encoded.size); out.write(encoded)
            }
            text(domain); out.writeInt(fields.size); fields.forEach(::text)
        }
        return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
