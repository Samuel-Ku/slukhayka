package com.slukhayka.audiobooks.data.diagnostics

enum class ProcessExitReason {
    UNKNOWN,
    EXIT_SELF,
    SIGNALED,
    LOW_MEMORY,
    CRASH,
    CRASH_NATIVE,
    ANR,
    INITIALIZATION_FAILURE,
    PERMISSION_CHANGE,
    EXCESSIVE_RESOURCE_USAGE,
    USER_REQUESTED,
    USER_STOPPED,
    DEPENDENCY_DIED,
    OTHER,
    FREEZER,
    PACKAGE_STATE_CHANGE,
    PACKAGE_UPDATED
}

enum class ProcessImportance {
    FOREGROUND,
    FOREGROUND_SERVICE,
    VISIBLE,
    PERCEPTIBLE,
    SERVICE,
    CACHED,
    GONE,
    UNKNOWN
}

data class ProcessExitSnapshot(
    val timestampMillis: Long,
    val reason: ProcessExitReason,
    val status: Int,
    val importance: ProcessImportance,
    val rssKb: Long,
    val pssKb: Long,
    val appVersionCode: Long,
    val androidApi: Int,
    val state: CrashContext
)

data class UnexpectedPlaybackExitEvent(
    val timestampMillis: Long,
    val reason: ProcessExitReason,
    val status: Int,
    val importance: ProcessImportance,
    val rssKb: Long,
    val pssKb: Long,
    val appVersionCode: Long,
    val androidApi: Int,
    val state: CrashContext
)

object UnexpectedExitPolicy {
    private val actionableReasons = setOf(
        ProcessExitReason.SIGNALED,
        ProcessExitReason.LOW_MEMORY,
        ProcessExitReason.EXCESSIVE_RESOURCE_USAGE,
        ProcessExitReason.DEPENDENCY_DIED
    )

    fun classify(exit: ProcessExitSnapshot): UnexpectedPlaybackExitEvent? {
        if (exit.androidApi < 30 || exit.reason !in actionableReasons) return null
        if (exit.state.playbackState !in setOf(
                DiagnosticPlaybackState.PLAYING,
                DiagnosticPlaybackState.BUFFERING
            )
        ) return null
        return UnexpectedPlaybackExitEvent(
            timestampMillis = exit.timestampMillis,
            reason = exit.reason,
            status = exit.status,
            importance = exit.importance,
            rssKb = exit.rssKb,
            pssKb = exit.pssKb,
            appVersionCode = exit.appVersionCode,
            androidApi = exit.androidApi,
            state = exit.state
        )
    }
}
