package com.slukhayka.audiobooks.data.source

import android.util.Log

/**
 * #779 — who actually runs yt-dlp.
 *
 * The seam exists so that swapping the engine is one implementation rather
 * than a rewrite: everything above it speaks in option lists, so the engine
 * only has to turn those into JSON.
 *
 * Production must NOT be an external binary. The in-process engine (bundled
 * CPython through Chaquopy) is the production one; [ProcessYtDlpEngine] is the
 * development fallback and the test double, and it is the only thing here that
 * needs `yt-dlp` on `PATH`.
 */
internal interface YtDlpEngine {
    /**
     * @param arguments the yt-dlp options AND the URL, WITHOUT the program
     *   name — an in-process engine imports the module instead of executing
     *   it, so a program name would be meaningless there.
     * @return the JSON yt-dlp wrote, or null on any failure.
     */
    suspend fun run(arguments: List<String>): String?
}

/**
 * #779 — the exact option lists, pure so the engine contract is provable
 * without an engine.
 *
 * Both are resolve-only: no bytes are ever downloaded here, and `--no-warnings`
 * keeps stdout to the JSON document alone.
 */
internal object YtDlpArguments {

    /** One video's metadata, including its formats (`-J`). */
    fun resolveJson(watchUrl: String): List<String> =
        listOf("-J", "--no-download", "--no-playlist", "--no-warnings", watchUrl)

    /**
     * A submitted link's flat metadata (`-J --flat-playlist`): the title, and
     * for a playlist its ordered entries with titles and URLs, WITHOUT
     * fetching each video's formats.
     */
    fun flatPlaylistJson(url: String): List<String> =
        listOf("-J", "--flat-playlist", "--no-download", "--no-warnings", url)
}

/**
 * The external-binary engine — development fallback only.
 *
 * A missing binary, a non-zero exit and a transport failure all collapse to
 * null, because every caller already reads null as «no honest answer» and
 * turns it into the honest refusal (ADR-0019); only cancellation propagates.
 */
internal object ProcessYtDlpEngine : YtDlpEngine {

    private const val PROGRAM = "yt-dlp"

    override suspend fun run(arguments: List<String>): String? = try {
        val process = ProcessBuilder(listOf(PROGRAM) + arguments)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (process.waitFor() != 0) null else output
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (t: Throwable) {
        Log.w("YtDlpExtractor", "$PROGRAM failed for ${arguments.lastOrNull()}", t)
        null
    }
}
