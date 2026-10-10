package com.slukhayka.audiobooks.data.recommend

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import java.io.File
import java.io.InputStream
import java.nio.LongBuffer
import java.security.MessageDigest

/**
 * The production [TextEmbedder] (spec-19 T3): multilingual-e5-small,
 * 384-dim, int8 ONNX, mean-pooled and L2-normalized — the model behind the
 * «Рекомендовано для вас» row once the eval gate says GO.
 *
 * The bundled model asset is fetched at build time by the
 * `downloadE5Model` Gradle task (118 MB int8 — over GitHub's file limit,
 * never committed); [create] returns null when the asset is absent so the
 * caller can fall back to the keyword baseline — the row must never crash
 * on a missing model (T2 contract).
 *
 * The model card prescribes `query: ` for symmetric semantic similarity.
 * Catalog books and listening signals share that input policy, including
 * the declared tokenizer boundary tokens and the 512-token total budget.
 *
 * Same `ai.onnxruntime` API on Android and desktop, so this single class
 * serves the app (assets) and the host-side eval script (a file path).
 */
class OnnxEmbedder private constructor(
    private val environment: OrtEnvironment,
    private val session: OrtSession,
    private val tokenizer: UnigramTokenizer,
    private val inputIdsName: String,
    private val attentionMaskName: String,
    private val tokenTypeIdsName: String?,
    private val outputName: String,
    private val prefix: String,
    override val cacheContext: EmbeddingContext
) : TextEmbedder, AutoCloseable {

    override fun embed(text: String): FloatArray {
        val ids = E5RecommendationInput.encode(text, tokenizer, prefix)
        val seqLen = ids.size
        val inputIds = LongBuffer.allocate(seqLen)
        val attention = LongBuffer.allocate(seqLen)
        val tokenTypes = LongBuffer.allocate(seqLen)
        for (i in 0 until seqLen) {
            inputIds.put(i, ids[i].toLong())
            attention.put(i, 1L)
            tokenTypes.put(i, 0L)
        }
        val shape = longArrayOf(1L, seqLen.toLong())
        val inputs = LinkedHashMap<String, OnnxTensor>()
        return try {
            inputs[inputIdsName] = OnnxTensor.createTensor(environment, inputIds, shape)
            inputs[attentionMaskName] = OnnxTensor.createTensor(environment, attention, shape)
            tokenTypeIdsName?.let {
                inputs[it] = OnnxTensor.createTensor(environment, tokenTypes, shape)
            }
            session.run(inputs).use { result ->
                val output = result.get(outputName).orElse(null) ?: return FloatArray(hiddenDim)
                val hidden = output.value as? Array<*> ?: return FloatArray(hiddenDim)
                meanPool(hidden, seqLen)
            }
        } catch (e: OrtException) {
            // Inference failure degrades to a zero vector — the item drops
            // out of ranking (cosine 0), never a crash (T2 contract).
            FloatArray(hiddenDim)
        } finally {
            inputs.values.forEach { it.close() }
        }
    }

    /**
     * Mean-pools the [1, seq, dim] last_hidden_state (masked by [seqLen]).
     * The Java binding returns the output as a primitive `float[][][]` —
     * `float[]` rows are NOT `Array<*>`, so each row is read through
     * `Number.toFloat()` over the Object view (Float[] boxes) when the
     * runtime returns boxed arrays, and the primitives path is safe either
     * way: every element is a Number.
     */
    private fun meanPool(hidden: Array<*>, seqLen: Int): FloatArray {
        val dim = hiddenDim
        val pooled = FloatArray(dim)
        val rows = (hidden[0] as? Array<*>) ?: return pooled
        for (i in 0 until minOf(seqLen, rows.size)) {
            val row = rows[i] ?: continue
            if (row is FloatArray) {
                for (d in 0 until minOf(dim, row.size)) pooled[d] += row[d]
            } else {
                val boxed = row as? Array<*> ?: continue
                for (d in 0 until minOf(dim, boxed.size)) {
                    pooled[d] += ((boxed[d] as? Number)?.toFloat() ?: 0f)
                }
            }
        }
        val norm = kotlin.math.sqrt(pooled.sumOf { (it * it).toDouble() })
        if (norm == 0.0) return pooled
        return FloatArray(dim) { (pooled[it] / norm).toFloat() }
    }

    override fun close() {
        session.close()
        environment.close()
    }

    companion object {
        private const val hiddenDim = 384

        /**
         * Creates the embedder from an on-disk model + tokenizer.json, or
         * null when the model file is absent. [prefix] is the e5 retrieval
         * prefix (default `query: ` for symmetric similarity); an explicit
         * prefix supports a separately defined asymmetric retrieval role.
         */
        fun fromFiles(modelFile: File, tokenizerFile: File, prefix: String = E5RecommendationInput.DEFAULT_PREFIX): OnnxEmbedder? {
            if (!modelFile.exists()) return null
            val environment = OrtEnvironment.getEnvironment()
            return try {
                val tokenizer = UnigramTokenizer.fromFile(tokenizerFile)
                tokenizer.encodeForModel("", 2) // Validate the declared template before allocating a native session.
                val context = E5RecommendationInput.cacheContext(sha(modelFile), sha(tokenizerFile), environment.version, prefix)
                val session = environment.createSession(modelFile.absolutePath)
                create(environment, session, tokenizer, prefix, context)
            } catch (e: Exception) {
                environment.close()
                null
            }
        }

        /**
         * Creates the embedder from model + tokenizer bytes (e.g. Android
         * assets). The caller owns the [environment] and closes it with the
         * embedder.
         */
        fun fromBytes(
            environment: OrtEnvironment,
            modelBytes: ByteArray,
            tokenizerBytes: ByteArray,
            prefix: String = E5RecommendationInput.DEFAULT_PREFIX
        ): OnnxEmbedder? {
            return try {
                val tokenizer = UnigramTokenizer.fromStream(tokenizerBytes.inputStream())
                tokenizer.encodeForModel("", 2)
                val context = E5RecommendationInput.cacheContext(sha(modelBytes), sha(tokenizerBytes), environment.version, prefix)
                val session = environment.createSession(modelBytes)
                create(environment, session, tokenizer, prefix, context)
            } catch (e: Exception) {
                null
            }
        }

        private fun sha(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

        private fun sha(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(65_536)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return hex(digest.digest())
        }

        private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

        private fun create(
            environment: OrtEnvironment,
            session: OrtSession,
            tokenizer: UnigramTokenizer,
            prefix: String,
            cacheContext: EmbeddingContext
        ): OnnxEmbedder {
            val inputNames = session.inputNames
            val inputIdsName = inputNames.firstOrNull { it.contains("input_ids") }
                ?: inputNames.firstOrNull { it.contains("ids") }
                ?: inputNames.first()
            val attentionMaskName = inputNames.firstOrNull { it.contains("attention_mask") }
                ?: inputNames.firstOrNull { it.contains("mask") }
                ?: inputNames.first()
            val tokenTypeIdsName = inputNames.firstOrNull { it.contains("token_type_ids") }
            val outputName = session.outputNames.firstOrNull { it.contains("last_hidden_state") }
                ?: session.outputNames.firstOrNull { it.contains("sentence_embedding") }
                ?: session.outputNames.firstOrNull { it.contains("output") }
                ?: session.outputNames.first()
            return OnnxEmbedder(
                environment, session, tokenizer,
                inputIdsName, attentionMaskName, tokenTypeIdsName, outputName, prefix, cacheContext
            )
        }
    }
}
