package com.slukhayka.audiobooks.data.diagnostics

enum class ExitReason {
    LOW_MEMORY,
    EXCESSIVE_RESOURCE_USE,
    SIGNALED,
    USER_REQUESTED,
    PACKAGE_UPDATED,
    NORMAL,
    OTHER
}

object UnexpectedPlaybackExitPolicy {
    private val actionable = setOf(
        ExitReason.LOW_MEMORY,
        ExitReason.EXCESSIVE_RESOURCE_USE,
        ExitReason.SIGNALED
    )

    fun shouldReport(
        reason: ExitReason,
        playbackWasActive: Boolean,
        exitTimestamp: Long,
        lastReportedTimestamp: Long
    ): Boolean = playbackWasActive && reason in actionable && exitTimestamp > lastReportedTimestamp
}

class UnexpectedPlaybackExit(reason: ExitReason) :
    RuntimeException("Unexpected playback exit: ${reason.name}")
