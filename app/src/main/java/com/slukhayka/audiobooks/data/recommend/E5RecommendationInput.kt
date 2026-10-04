package com.slukhayka.audiobooks.data.recommend

import java.nio.ByteBuffer
import java.security.MessageDigest

/** The one input policy shared by catalog and signal embeddings. */
object E5RecommendationInput {
    const val DEFAULT_PREFIX = "query: "
    const val MAX_LENGTH = 512

    fun cacheContext(modelSha256: String, tokenizerSha256: String, runtimeVersion: String,
        prefix: String = DEFAULT_PREFIX): EmbeddingContext {
        require(listOf(modelSha256, tokenizerSha256).all { it.matches(Regex("[a-f0-9]{64}")) })
        require(runtimeVersion.isNotBlank())
        val digest = MessageDigest.getInstance("SHA-256")
        // Version includes the NFKC approximation, declared boundary framing,
        // total token budget, prefix role and masked mean/L2 pooling contract.
        for (field in listOf("e5-input-v2-nfkc-template-mean-l2", modelSha256, tokenizerSha256,
            runtimeVersion, prefix, MAX_LENGTH.toString())) {
            val bytes = field.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        val key = digest.digest().joinToString("") { "%02x".format(it) }
        return EmbeddingContext("onnx-e5-v2:$key", 384)
    }

    fun encode(text: String, tokenizer: UnigramTokenizer, prefix: String = DEFAULT_PREFIX): IntArray =
        tokenizer.encodeForModel(prefix + text, MAX_LENGTH)
}
