package com.slukhayka.audiobooks.data.diagnostics

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics

class FirebaseCrashReportSink private constructor(
    private val crashlytics: FirebaseCrashlytics
) : CrashReportSink {
    override fun setCollectionEnabled(enabled: Boolean) {
        crashlytics.setCrashlyticsCollectionEnabled(enabled)
    }

    override fun checkForUnsentReports(onResult: (Boolean) -> Unit) {
        crashlytics.checkForUnsentReports()
            .addOnSuccessListener(onResult)
            .addOnFailureListener { onResult(false) }
    }

    override fun sendUnsentReports() = crashlytics.sendUnsentReports()
    override fun deleteUnsentReports() = crashlytics.deleteUnsentReports()

    override fun setCustomKeys(keys: Map<String, String>) {
        keys.forEach { (key, value) -> crashlytics.setCustomKey(key, value) }
    }

    override fun record(exception: Throwable) = crashlytics.recordException(exception)

    companion object {
        fun create(context: Context): CrashReportSink {
            val app = FirebaseApp.getApps(context).firstOrNull()
                ?: FirebaseApp.initializeApp(context)
                ?: return NoOpCrashReportSink
            return FirebaseCrashReportSink(FirebaseCrashlytics.getInstance())
        }
    }
}

object NoOpCrashReportSink : CrashReportSink {
    override fun setCollectionEnabled(enabled: Boolean) = Unit
    override fun checkForUnsentReports(onResult: (Boolean) -> Unit) = onResult(false)
    override fun sendUnsentReports() = Unit
    override fun deleteUnsentReports() = Unit
    override fun setCustomKeys(keys: Map<String, String>) = Unit
    override fun record(exception: Throwable) = Unit
}
