package com.slukhayka.audiobooks.data.db

import android.os.Build
import java.io.File

/** CI evidence that incompatible Robolectric/native SQLite cohorts use distinct JVMs. */
object NativeRoomWorkerIdentity {
    fun record() {
        val target = System.getProperty("slukhayka.test.workerIdentityFile") ?: return
        val cohort = "sdk=${Build.VERSION.SDK_INT};sqlite=framework"
        File(target).appendText("${ProcessHandle.current().pid()}\t$cohort\n")
    }
}
