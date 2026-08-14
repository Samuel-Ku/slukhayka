package com.example.data.recommend

/**
 * The embedding seam of the on-device recommendation row (spec-19 Track A,
 * Q4). Anything that turns a text into a fixed-dimension vector plugs in
 * here: today the [KeywordEmbedder] baseline (genre+author similarity — the
 * Q6 baseline to beat), tomorrow an on-device int8 ONNX multilingual-e5
 * model behind the same interface. Pure JVM, deterministic, testable.
 */
interface TextEmbedder {
    fun embed(text: String): FloatArray
}

/**
 * Baseline embedder (spec-19 Q6: "similar by genre+author" must lose to the
 * embedding row). A deterministic bag-of-tokens vector: normalized word
 * counts over the lowercased text's letter/digit tokens, in a fixed
 * alphabet. Cheap, stable, and a fair target for the real model to beat.
 */
class KeywordEmbedder : TextEmbedder {

    override fun embed(text: String): FloatArray {
        val counts = LinkedHashMap<String, Int>()
        for (token in TOKEN_SPLIT.findAll(text.lowercase())) {
            val word = token.value
            if (STOP_WORDS.contains(word)) continue
            counts[word] = (counts[word] ?: 0) + 1
        }
        val vector = FloatArray(ALPHABET_SIZE)
        for ((word, count) in counts) {
            val index = stableIndex(word)
            if (index >= 0) vector[index] += count.toFloat()
        }
        // L2-normalize so cosine is meaningful.
        val norm = kotlin.math.sqrt(vector.sumOf { (it * it).toDouble() })
        if (norm == 0.0) return vector
        return FloatArray(ALPHABET_SIZE) { (vector[it] / norm).toFloat() }
    }

    private fun stableIndex(word: String): Int {
        var hash = 0
        for (c in word) hash = (hash * 31 + c.code) and Int.MAX_VALUE
        return hash % ALPHABET_SIZE
    }

    companion object {
        /** Fixed vector width — enough buckets for a few-thousand-book catalogue. */
        const val ALPHABET_SIZE = 512

        private val TOKEN_SPLIT = Regex("[\\p{L}\\p{N}]+")
        private val STOP_WORDS = setOf(
            "та", "і", "й", "а", "в", "у", "на", "з", "до", "по", "за",
            "не", "це", "що", "як", "для", "про", "від", "книга", "книги",
            "аудіокнига", "аудіокниги", "the", "and", "of", "to", "a", "in"
        )
    }
}
