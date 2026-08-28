package com.slukhayka.audiobooks.data.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build

interface ExitInspectionLedger : PlaybackActivityMarker {
    fun playbackWasActive(): Boolean
    fun lastInspectedExitTimestamp(): Long
    fun markExitInspected(timestamp: Long)
}

class CrashDiagnosticLedger(context: Context) : ExitInspectionLedger {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun playbackWasActive(): Boolean = prefs.getBoolean(KEY_PLAYBACK_ACTIVE, false)
    override fun setPlaybackActive(active: Boolean) =
        prefs.edit().putBoolean(KEY_PLAYBACK_ACTIVE, active).apply()
    override fun lastInspectedExitTimestamp(): Long = prefs.getLong(KEY_LAST_EXIT, 0L)
    override fun markExitInspected(timestamp: Long) =
        prefs.edit().putLong(KEY_LAST_EXIT, timestamp).apply()

    private companion object {
        const val PREFS_NAME = "crash_diagnostic_ledger"
        const val KEY_PLAYBACK_ACTIVE = "playback_active"
        const val KEY_LAST_EXIT = "last_exit_timestamp"
    }
}

data class HistoricalProcessExit(val timestamp: Long, val reason: ExitReason)

fun interface ProcessExitHistory {
    fun newest(): HistoricalProcessExit?
}

class AndroidProcessExitHistory(private val context: Context) : ProcessExitHistory {
    override fun newest(): HistoricalProcessExit? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return null
        return activityManager.getHistoricalProcessExitReasons(context.packageName, 0, 5)
            .maxByOrNull(ApplicationExitInfo::getTimestamp)
            ?.let { HistoricalProcessExit(it.timestamp, exitReason(it.reason)) }
    }

    private fun exitReason(reason: Int): ExitReason = when (reason) {
        ApplicationExitInfo.REASON_LOW_MEMORY -> ExitReason.LOW_MEMORY
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> ExitReason.EXCESSIVE_RESOURCE_USE
        ApplicationExitInfo.REASON_SIGNALED -> ExitReason.SIGNALED
        ApplicationExitInfo.REASON_USER_REQUESTED,
        ApplicationExitInfo.REASON_USER_STOPPED -> ExitReason.USER_REQUESTED
        ApplicationExitInfo.REASON_PACKAGE_UPDATED,
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> ExitReason.PACKAGE_UPDATED
        ApplicationExitInfo.REASON_EXIT_SELF -> ExitReason.NORMAL
        else -> ExitReason.OTHER
    }
}

/** Deduplicates platform exit evidence behind a fakeable history seam. */
class UnexpectedPlaybackExitDetector(
    private val exitHistory: ProcessExitHistory,
    private val ledger: ExitInspectionLedger
) {
    fun inspect(reporting: CrashReporting) {
        val exit = exitHistory.newest() ?: return
        val lastInspected = ledger.lastInspectedExitTimestamp()
        if (UnexpectedPlaybackExitPolicy.shouldReport(
                exit.reason,
                ledger.playbackWasActive(),
                exit.timestamp,
                lastInspected
            )
        ) {
            reporting.record(UnexpectedPlaybackExit(exit.reason))
        }
        if (exit.timestamp > lastInspected) ledger.markExitInspected(exit.timestamp)
        ledger.setPlaybackActive(false)
    }
}
