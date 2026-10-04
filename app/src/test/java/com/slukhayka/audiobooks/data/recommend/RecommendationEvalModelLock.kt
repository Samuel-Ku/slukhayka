package com.slukhayka.audiobooks.data.recommend

import java.io.File

/** Identity of the semantic backend is mandatory, even when an asset already exists. */
object RecommendationEvalModelLock {
    const val REVISION = "761b726dd34fb83930e26aab4e9ac3899aa1fa78"
    const val MODEL_SHA256 = "f80102d3f2a1229f387d3c81909990d8945513e347b0eab049f7de3c6f98c193"
    const val TOKENIZER_SHA256 = "0b44a9d7b51c3c62626640cda0e2c2f70fdacdc25bbbd68038369d14ebdf4c39"

    fun verify(model: File, tokenizer: File) {
        require(model.isFile && RecommendationFeedSnapshot.sha256(model) == MODEL_SHA256) {
            "Semantic model is absent or differs from the pinned E5 revision $REVISION"
        }
        require(tokenizer.isFile && RecommendationFeedSnapshot.sha256(tokenizer) == TOKENIZER_SHA256) {
            "Semantic tokenizer is absent or differs from the pinned E5 revision $REVISION"
        }
    }
    const val RUNTIME_SHA256 = "5b57b8c6303303f8d8cbed7f787f31611718b746ab2fa45c888c9df153f1b3c2"

    fun verifyRuntime(artifact: File) {
        require(artifact.isFile && RecommendationFeedSnapshot.sha256(artifact) == RUNTIME_SHA256) {
            "Desktop ONNX Runtime must be the verified Maven 1.21.0 artifact"
        }
    }

}
