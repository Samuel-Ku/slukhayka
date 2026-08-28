package com.slukhayka.audiobooks.data.diagnostics

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

    override fun setContext(context: CrashContext) {
        context.customKeys.forEach { (key, value) -> crashlytics.setCustomKey(key, value) }
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
}
