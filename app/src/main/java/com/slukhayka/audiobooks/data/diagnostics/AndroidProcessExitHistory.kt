package com.slukhayka.audiobooks.data.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build

fun interface ProcessStateSummarySink {
    fun set(context: CrashContext)
}

object ProcessStateSummaryCodec {
    private const val VERSION = "v1"
    private const val MAX_BYTES = 128

    fun encode(
        context: CrashContext,
        appVersionCode: Long,
        androidApi: Int
    ): ByteArray = listOf(
        VERSION,
        appVersionCode,
        androidApi,
        context.appVisibility.wireValue,
        context.playbackState.wireValue,
        context.playbackService.wireValue,
        context.audioOrigin.wireValue,
        context.castActive.toString()
    ).joinToString("|").encodeToByteArray().also { encoded ->
        check(encoded.size <= MAX_BYTES)
    }

    fun decode(summary: ByteArray): StoredProcessState? {
        if (summary.size > MAX_BYTES) return null
        val decoded = runCatching {
            summary.decodeToString(throwOnInvalidSequence = true)
        }.getOrNull() ?: return null
        val parts = decoded.split('|')
        if (parts.size != 8 || parts[0] != VERSION) return null
        val appVersionCode = parts[1].toLongOrNull()?.takeIf { it >= 0L } ?: return null
        val androidApi = parts[2].toIntOrNull()?.takeIf { it >= 30 } ?: return null
        val visibility = AppVisibility.entries.singleOrNull { it.wireValue == parts[3] } ?: return null
        val playback = DiagnosticPlaybackState.entries.singleOrNull { it.wireValue == parts[4] } ?: return null
        val service = DiagnosticPlaybackService.entries.singleOrNull { it.wireValue == parts[5] } ?: return null
        val origin = DiagnosticAudioOrigin.entries.singleOrNull { it.wireValue == parts[6] } ?: return null
        val castActive = when (parts[7]) {
            "true" -> true
            "false" -> false
            else -> return null
        }
        return StoredProcessState(
            appVersionCode = appVersionCode,
            androidApi = androidApi,
            context = CrashContext(visibility, playback, service, origin, castActive)
        )
    }
}

data class StoredProcessState(
    val appVersionCode: Long,
    val androidApi: Int,
    val context: CrashContext
)

class AndroidHistoricalProcessExitSource(
    context: Context
) : HistoricalProcessExitSource, ProcessStateSummarySink {
    private val appContext = context.applicationContext
    private val activityManager = appContext.getSystemService(ActivityManager::class.java)

    override val supported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    override fun latest(): ProcessExitSnapshot? {
        if (!supported) return null
        return runCatching { readLatest() }.getOrNull()
    }

    private fun readLatest(): ProcessExitSnapshot? {
        val info = activityManager.getHistoricalProcessExitReasons(null, 0, 1).firstOrNull()
            ?: return null
        val summary = info.processStateSummary ?: return null
        val storedState = ProcessStateSummaryCodec.decode(summary) ?: return null
        return ProcessExitSnapshot(
            timestampMillis = info.timestamp,
            reason = info.reason.toBoundedReason(),
            status = info.status,
            importance = info.importance.toBoundedImportance(),
            rssKb = info.rss,
            pssKb = info.pss,
            appVersionCode = storedState.appVersionCode,
            androidApi = storedState.androidApi,
            state = storedState.context
        )
    }

    override fun set(context: CrashContext) {
        if (supported) {
            runCatching {
                activityManager.setProcessStateSummary(
                    ProcessStateSummaryCodec.encode(
                        context = context,
                        appVersionCode = appVersionCode(),
                        androidApi = Build.VERSION.SDK_INT
                    )
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun appVersionCode(): Long {
        val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else info.versionCode.toLong()
    }
}

private fun Int.toBoundedReason(): ProcessExitReason = when (this) {
    ApplicationExitInfo.REASON_EXIT_SELF -> ProcessExitReason.EXIT_SELF
    ApplicationExitInfo.REASON_SIGNALED -> ProcessExitReason.SIGNALED
    ApplicationExitInfo.REASON_LOW_MEMORY -> ProcessExitReason.LOW_MEMORY
    ApplicationExitInfo.REASON_CRASH -> ProcessExitReason.CRASH
    ApplicationExitInfo.REASON_CRASH_NATIVE -> ProcessExitReason.CRASH_NATIVE
    ApplicationExitInfo.REASON_ANR -> ProcessExitReason.ANR
    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> ProcessExitReason.INITIALIZATION_FAILURE
    ApplicationExitInfo.REASON_PERMISSION_CHANGE -> ProcessExitReason.PERMISSION_CHANGE
    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> ProcessExitReason.EXCESSIVE_RESOURCE_USAGE
    ApplicationExitInfo.REASON_USER_REQUESTED -> ProcessExitReason.USER_REQUESTED
    ApplicationExitInfo.REASON_USER_STOPPED -> ProcessExitReason.USER_STOPPED
    ApplicationExitInfo.REASON_DEPENDENCY_DIED -> ProcessExitReason.DEPENDENCY_DIED
    ApplicationExitInfo.REASON_OTHER -> ProcessExitReason.OTHER
    ApplicationExitInfo.REASON_FREEZER -> ProcessExitReason.FREEZER
    ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> ProcessExitReason.PACKAGE_STATE_CHANGE
    ApplicationExitInfo.REASON_PACKAGE_UPDATED -> ProcessExitReason.PACKAGE_UPDATED
    else -> ProcessExitReason.UNKNOWN
}

private fun Int.toBoundedImportance(): ProcessImportance = when (this) {
    ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> ProcessImportance.FOREGROUND
    ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> ProcessImportance.FOREGROUND_SERVICE
    ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> ProcessImportance.VISIBLE
    ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> ProcessImportance.PERCEPTIBLE
    ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> ProcessImportance.SERVICE
    ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> ProcessImportance.CACHED
    ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE -> ProcessImportance.GONE
    else -> ProcessImportance.UNKNOWN
}
