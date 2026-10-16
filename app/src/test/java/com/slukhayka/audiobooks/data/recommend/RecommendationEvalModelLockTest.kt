package com.slukhayka.audiobooks.data.recommend

import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.file.Files

class RecommendationEvalModelLockTest {
    @Test
    fun `an existing but altered model cannot be treated as the pinned semantic backend`() {
        val directory = Files.createTempDirectory("eval-model-lock").toFile()
        try {
            val model = directory.resolve("model.onnx").apply { writeText("wrong model") }
            val tokenizer = directory.resolve("tokenizer.json").apply { writeText("wrong tokenizer") }
            assertThrows(IllegalArgumentException::class.java) {
                RecommendationEvalModelLock.verify(model, tokenizer)
            }
        } finally { directory.deleteRecursively() }
    }
    @Test
    fun `an unverified desktop runtime cannot produce semantic evidence`() {
        val file = Files.createTempFile("eval-runtime", ".jar").toFile()
        try {
            file.writeText("substituted runtime")
            assertThrows(IllegalArgumentException::class.java) {
                RecommendationEvalModelLock.verifyRuntime(file)
            }
        } finally { file.delete() }
    }

}
