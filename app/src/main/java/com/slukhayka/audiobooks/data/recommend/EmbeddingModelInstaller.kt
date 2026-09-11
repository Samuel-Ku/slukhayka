package com.slukhayka.audiobooks.data.recommend

import com.slukhayka.audiobooks.data.source.HttpFetcher
import java.io.File

/** The honest state of the on-device embedding model (spec-19 / #483). */
sealed interface EmbeddingModelState {
    /** Nothing installed yet — the simplified (keyword) mode is active. */
    data object NotInstalled : EmbeddingModelState

    /** A one-time download is running; [progress] is 0..1 when known. */
    data class Downloading(val progress: Float? = null) : EmbeddingModelState

    /** The full ONNX model is on disk and the embedder can use it. */
    data object Installed : EmbeddingModelState

    /** The last attempt failed; the simplified mode stays visible. */
    data class Failed(val reason: String) : EmbeddingModelState
}

/**
 * #483 — installs the multilingual-e5-small ONNX model on demand, from the
 * official HuggingFace source through the app's shared transport, into a
 * private app directory. The model is never committed to the repository and
 * never packaged in the APK; the download starts only when a listener opens
 * the recommendations (Overview or settings) and is idempotent — an already
 * installed model is never re-fetched. Best-effort and honest: a failure
 * leaves the previous state and the simplified mode visible, and the listener
 * can retry.
 */
class EmbeddingModelInstaller(
    private val dir: File,
    private val fetcher: HttpFetcher,
    private val onState: (EmbeddingModelState) -> Unit = {}
) {

    private val modelFile: File get() = File(dir, MODEL_NAME)
    private val tokenizerFile: File get() = File(dir, TOKENIZER_NAME)

    /** Whether a complete, non-truncated model is already on disk. */
    fun isInstalled(): Boolean =
        modelFile.length() >= MIN_MODEL_BYTES && tokenizerFile.length() > 0L

    /**
     * Ensures the model is installed, downloading it once when absent. Safe
     * to call repeatedly; a concurrent caller is expected to be serialized by
     * the caller's single-flight guard. Returns the resulting state.
     */
    suspend fun ensureInstalled(): EmbeddingModelState {
        if (isInstalled()) {
            onState(EmbeddingModelState.Installed)
            return EmbeddingModelState.Installed
        }
        onState(EmbeddingModelState.Downloading(null))
        val result = try {
            dir.mkdirs()
            download(MODEL_URL, modelFile) { progress -> onState(EmbeddingModelState.Downloading(progress)) }
            download(TOKENIZER_URL, tokenizerFile, onProgress = null)
            if (isInstalled()) EmbeddingModelState.Installed else EmbeddingModelState.Failed("incomplete")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            EmbeddingModelState.Failed(e.message ?: "download failed")
        }
        onState(result)
        return result
    }

    private fun download(url: String, target: File, onProgress: ((Float) -> Unit)?) {
        val sized = fetcher.getSizedStream(url) ?: error("no stream for $url")
        val tmp = File(dir, target.name + ".part")
        sized.stream.use { input ->
            tmp.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                val total = sized.contentLength?.takeIf { it > 0L }
                var read = 0L
                var reported = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n == 0) continue
                    out.write(buffer, 0, n)
                    read += n
                    if (onProgress != null && total != null && read - reported >= total / 100) {
                        reported = read
                        onProgress(read.toFloat() / total)
                    }
                }
            }
        }
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    companion object {
        const val MODEL_NAME = "model.onnx"
        const val TOKENIZER_NAME = "tokenizer.json"

        // The exact artifacts the dev-time downloadE5Model Gradle task uses
        // (int8 multilingual-e5-small), so a runtime install and a bundled
        // asset are byte-identical.
        const val MODEL_URL =
            "https://huggingface.co/Xenova/multilingual-e5-small/resolve/main/onnx/model_quantized.onnx"
        const val TOKENIZER_URL =
            "https://huggingface.co/Xenova/multilingual-e5-small/resolve/main/tokenizer.json"

        /** Guards against a truncated or HTML-error body being treated as the model. */
        const val MIN_MODEL_BYTES = 1_000_000L
    }
}
