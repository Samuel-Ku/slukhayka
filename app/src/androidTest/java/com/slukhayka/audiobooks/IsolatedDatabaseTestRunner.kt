package com.slukhayka.audiobooks

import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner
import com.slukhayka.audiobooks.data.db.AudiobookDatabase

/**
 * The instrumentation runner every device test runs under.
 *
 * It points the app at a SEPARATE database file before the first open, for
 * one reason: the accessibility journey legitimately wipes and reseeds the
 * database to get a deterministic screen, and it used to do that to the
 * listener's real library. Running device tests on a real phone destroyed
 * the listener's books, progress and bookmarks; this runner makes that
 * impossible by construction rather than by asking each test to be careful.
 *
 * The override lands in [AndroidJUnitRunner.onCreate], which the framework
 * calls before `Application.onCreate` — and the app opens its database
 * lazily, so nothing has touched it yet. The scratch file is deleted on the
 * way in, so every run starts from a known-empty database.
 *
 * Production is untouched: `databaseNameOverride` stays null outside tests.
 */
class IsolatedDatabaseTestRunner : AndroidJUnitRunner() {

    override fun onCreate(arguments: Bundle) {
        AudiobookDatabase.databaseNameOverride = TEST_DATABASE_NAME
        // Fresh scratch file per run: nothing survives from a previous suite.
        targetContext.deleteDatabase(TEST_DATABASE_NAME)
        super.onCreate(arguments)
    }

    companion object {
        /**
         * Deliberately NOT the production name (`read4_audiobook_database`)
         * — that separation is the whole point of this runner.
         */
        const val TEST_DATABASE_NAME = "read4_audiobook_database_test"
    }
}
