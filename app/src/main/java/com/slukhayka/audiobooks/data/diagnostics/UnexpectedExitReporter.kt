package com.slukhayka.audiobooks.data.diagnostics

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

interface HistoricalProcessExitSource {
    val supported: Boolean
    fun latest(): ProcessExitSnapshot?
}

data class ProcessExitCursor(
    val timestampMillis: Long,
    val stableHash: String
)

interface ProcessExitCursorStore {
    fun load(): ProcessExitCursor?
    fun save(cursor: ProcessExitCursor)
}

class UnexpectedExitReporter(
    private val source: HistoricalProcessExitSource,
    private val cursorStore: ProcessExitCursorStore,
    private val crashReporting: CrashReporting,
    private val enabledForBuild: Boolean
) {
    fun inspectLatest() {
        if (!enabledForBuild || !source.supported) return

        val exit = source.latest() ?: return
        val cursor = ProcessExitCursor(exit.timestampMillis, exit.stableHash())
        val previous = cursorStore.load()
        if (previous != null && (
                exit.timestampMillis < previous.timestampMillis ||
                    exit.timestampMillis == previous.timestampMillis &&
                    cursor.stableHash == previous.stableHash
                )
        ) return

        cursorStore.save(cursor)
        UnexpectedExitPolicy.classify(exit)?.let(crashReporting::reportUnexpectedPlaybackExit)
    }
}

private fun ProcessExitSnapshot.stableHash(): String {
    val canonical = listOf(
        timestampMillis,
        reason.name,
        status,
        importance.name,
        rssKb,
        pssKb,
        appVersionCode,
        androidApi,
        state.appVisibility.wireValue,
        state.playbackState.wireValue,
        state.playbackService.wireValue,
        state.audioOrigin.wireValue,
        state.castActive
    ).joinToString(separator = "\u001f")
    return MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
}
