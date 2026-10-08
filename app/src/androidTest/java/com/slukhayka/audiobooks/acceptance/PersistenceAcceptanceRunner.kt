package com.slukhayka.audiobooks.acceptance

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/** #620: no production App composition, background sync, or shared-base writers. */
class PersistenceAcceptanceRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
        super.newApplication(cl, Application::class.java.name, context)
}
