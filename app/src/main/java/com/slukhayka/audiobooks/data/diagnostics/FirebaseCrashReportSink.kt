package com.slukhayka.audiobooks.data.diagnostics

import com.google.firebase.crashlytics.CustomKeysAndValues
import com.google.firebase.crashlytics.FirebaseCrashlytics

/** Firebase adapter kept behind [CrashReportSink] so tests never touch the SDK. */
class FirebaseCrashReportSink private constructor(
    private val crashlytics: FirebaseCrashlytics
) : CrashReportSink {

    override fun setCollectionEnabled(enabled: Boolean) {
        crashlytics.setCrashlyticsCollectionEnabled(enabled)
    }

    override fun checkForUnsentReports(onResult: (Boolean) -> Unit) {
        crashlytics.checkForUnsentReports().addOnCompleteListener { task ->
            onResult(task.isSuccessful && task.result == true)
        }
    }

    override fun sendUnsentReports() {
        crashlytics.sendUnsentReports()
    }

    override fun deleteUnsentReports() {
        crashlytics.deleteUnsentReports()
    }

    override fun setCustomKeys(keys: Map<String, String>) {
        keys.forEach { (key, value) -> crashlytics.setCustomKey(key, value) }
    }

    override fun record(exception: Throwable) { crashlytics.recordException(exception) }

    override fun setContext(context: CrashContext) {
        context.customKeys.forEach { (key, value) -> crashlytics.setCustomKey(key, value) }
    }

    override fun recordUnexpectedPlaybackExit(event: UnexpectedPlaybackExitEvent) {
        val keys = CustomKeysAndValues.Builder()
            .putString("exit_reason", event.reason.name)
            .putInt("exit_status", event.status)
            .putString("process_importance", event.importance.name)
            .putLong("rss_kb", event.rssKb)
            .putLong("pss_kb", event.pssKb)
            .putLong("app_version_code", event.appVersionCode)
            .putInt("android_api", event.androidApi)
            .apply {
                event.state.customKeys.forEach { (key, value) -> putString(key, value) }
            }
            .build()
        crashlytics.recordException(ReportedUnexpectedPlaybackExit(), keys)
    }

    companion object {
        /** Missing Firebase configuration must never make the local app fail. */
        fun createOrNoOp(): CrashReportSink = runCatching {
            FirebaseCrashReportSink(FirebaseCrashlytics.getInstance())
        }.getOrElse { NoOpCrashReportSink }
    }
}

private object NoOpCrashReportSink : CrashReportSink {
    override fun setCollectionEnabled(enabled: Boolean) = Unit
    override fun checkForUnsentReports(onResult: (Boolean) -> Unit) = onResult(false)
    override fun sendUnsentReports() = Unit
    override fun deleteUnsentReports() = Unit
    override fun setContext(context: CrashContext) = Unit
    override fun recordUnexpectedPlaybackExit(event: UnexpectedPlaybackExitEvent) = Unit
}

private class ReportedUnexpectedPlaybackExit : RuntimeException("Unexpected background playback exit")
