package com.slukhayka.audiobooks.data.diagnostics

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1709 — the exit cursor is written from the startup path, so its save must
 * not perform the synchronous `commit()` (a disk write + fsync on the caller's
 * thread). `apply()` updates the in-memory map at once, which is what the
 * reporter's dedupe reads; the disk flush is the platform's business.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class SharedPreferencesProcessExitCursorStoreTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun clearWatermark() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun `save uses apply, never the synchronous commit, and load sees the cursor at once`() {
        val prefs = RecordingPreferences(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
        val store = SharedPreferencesProcessExitCursorStore(RecordingContext(context, prefs))
        val cursor = ProcessExitCursor(1_725_000_123_456L, "a".repeat(64))

        store.save(cursor)

        assertEquals(listOf("apply"), prefs.terminalCalls)
        assertEquals(cursor, store.load())
        assertEquals(cursor, SharedPreferencesProcessExitCursorStore(RecordingContext(context, prefs)).load())
    }

    @Test
    fun `missing or incomplete watermark is no cursor`() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val store = SharedPreferencesProcessExitCursorStore(RecordingContext(context, prefs))
        assertNull(store.load())

        prefs.edit().putLong(KEY_TIMESTAMP, 1_725_000_123_456L).commit()
        assertNull(store.load())
    }

    private companion object {
        const val PREFS_NAME = "crash_exit_cursor"
        const val KEY_TIMESTAMP = "timestamp"
    }
}

/** Returns [prefs] for every name, so the store's internal lookup is intercepted. */
private class RecordingContext(
    base: Context,
    private val prefs: SharedPreferences
) : ContextWrapper(base) {
    override fun getApplicationContext(): Context = this

    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
}

/** Records which terminal editor call the production code chose. */
private class RecordingPreferences(
    private val delegate: SharedPreferences
) : SharedPreferences by delegate {

    val terminalCalls = mutableListOf<String>()

    override fun edit(): SharedPreferences.Editor =
        RecordingEditor(delegate.edit(), terminalCalls)
}

private class RecordingEditor(
    private val delegate: SharedPreferences.Editor,
    private val terminalCalls: MutableList<String>
) : SharedPreferences.Editor by delegate {

    override fun commit(): Boolean {
        terminalCalls += "commit"
        return delegate.commit()
    }

    override fun apply() {
        terminalCalls += "apply"
        delegate.apply()
    }
}
