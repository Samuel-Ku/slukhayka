package com.slukhayka.audiobooks.data.recommend

import com.slukhayka.audiobooks.data.source.HttpFetcher
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #483 — the installer downloads the model exactly once, never re-fetches an
 * installed model, and reports an honest Failed state on a broken download.
 */
class EmbeddingModelInstallerTest {

    private class FakeFetcher(private val bodies: Map<String, ByteArray>) : HttpFetcher() {
        var calls = 0
            private set

        override fun getSizedStream(url: String, extraHeaders: Map<String, String>): SizedStream? {
            val bytes = bodies[url] ?: return null
            calls++
            return SizedStream(ByteArrayInputStream(bytes), bytes.size.toLong())
        }
    }

    private fun tempDir(): File = Files.createTempDirectory("e5-model").toFile()

    private val modelBytes = ByteArray(1_000_001) { 0x1 }
    private val tokenizerBytes = ByteArray(16) { 0x2 }

    @Test
    fun `install downloads once and is idempotent`() = runBlocking {
        val dir = tempDir()
        val fetcher = FakeFetcher(
            mapOf(
                EmbeddingModelInstaller.MODEL_URL to modelBytes,
                EmbeddingModelInstaller.TOKENIZER_URL to tokenizerBytes
            )
        )
        val states = mutableListOf<EmbeddingModelState>()
        val installer = EmbeddingModelInstaller(dir, fetcher, onState = { states += it })

        assertFalse(installer.isInstalled())
        assertEquals(EmbeddingModelState.Installed, installer.ensureInstalled())
        assertTrue(installer.isInstalled())
        assertEquals(2, fetcher.calls)
        assertTrue(states.any { it is EmbeddingModelState.Downloading })

        // A second pass does not touch the network again.
        assertEquals(EmbeddingModelState.Installed, installer.ensureInstalled())
        assertEquals(2, fetcher.calls)
    }

    @Test
    fun `a broken download reports Failed and stays uninstalled`() = runBlocking {
        val dir = tempDir()
        val fetcher = FakeFetcher(emptyMap())
        val states = mutableListOf<EmbeddingModelState>()
        val installer = EmbeddingModelInstaller(dir, fetcher, onState = { states += it })

        val result = installer.ensureInstalled()

        assertTrue(result is EmbeddingModelState.Failed)
        assertFalse(installer.isInstalled())
    }

    @Test
    fun `a truncated model body is not treated as installed`() = runBlocking {
        val dir = tempDir()
        val fetcher = FakeFetcher(
            mapOf(
                EmbeddingModelInstaller.MODEL_URL to ByteArray(10),
                EmbeddingModelInstaller.TOKENIZER_URL to tokenizerBytes
            )
        )
        val installer = EmbeddingModelInstaller(dir, fetcher)

        assertTrue(installer.ensureInstalled() is EmbeddingModelState.Failed)
        assertFalse(installer.isInstalled())
    }
}
