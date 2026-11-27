package com.slukhayka.audiobooks.data.recommend

import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import okio.Buffer

/** One transport/cache object, binding exact graph text and its detached signature. */
object SignedGraphEnvelope {
    // JSON escaping can double an already-encoded graph; the decoded graph still has its 8 MiB cap.
    const val MAX_BYTES = 2 * PrecomputedRecommendationGraph.MAX_BYTES + 8192
    private const val MAX_SIGNATURE_BYTES = 4096
    data class Payload(val graph: String, val signature: String)

    fun decode(text: String): Payload? = runCatching {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        val document = JsonReader.of(Buffer().writeUtf8(text)).use { reader ->
            reader.readJsonValue().also { require(reader.peek() == JsonReader.Token.END_DOCUMENT) }
        } as? Map<*, *> ?: return null
        require(document["formatVersion"] == 1.0)
        val graph = document["graph"] as? String ?: return null
        val signature = document["signature"] as? String ?: return null
        require(graph.toByteArray(Charsets.UTF_8).size <= PrecomputedRecommendationGraph.MAX_BYTES)
        require(signature.toByteArray(Charsets.UTF_8).size in 1..MAX_SIGNATURE_BYTES)
        Payload(graph, signature)
    }.getOrNull()

    fun encode(graph: String, signature: String): String {
        val buffer = Buffer()
        JsonWriter.of(buffer).use { writer ->
            writer.beginObject()
            writer.name("formatVersion").value(1L)
            writer.name("graph").value(graph)
            writer.name("signature").value(signature)
            writer.endObject()
        }
        return buffer.readUtf8()
    }
}
