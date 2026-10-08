package com.slukhayka.audiobooks.data.entries

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * spec-52 US28 / #1174 — the undo note itself: what the mark took away is
 * remembered EXACTLY, and anything the app cannot read is a note it does not
 * trust.
 *
 * The interesting half is the refusal: a corrupted or unknown state must not be
 * guessed at (ADR-0014) — [SharedPreferencesAbandonUndo.recall] then answers
 * "no note", and the cancel falls back to the state the evidence proves. The
 * rows below are written raw, the way a truncated or half-migrated store would
 * leave them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AbandonUndoTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val undo = SharedPreferencesAbandonUndo(context)

    @Before
    fun clearNote() {
        undo.forget(BOOK_ID)
    }

    /** Writes the note the way an older or damaged store would have left it. */
    private fun writeRaw(value: String) {
        context.getSharedPreferences("abandon_undo", Context.MODE_PRIVATE)
            .edit()
            .putString("before:$BOOK_ID", value)
            .commit()
    }

    @Test
    fun `a pass that existed is remembered with its own state`() {
        undo.remember(BOOK_ID, AbandonUndo.BeforeMark(existed = true, state = ReadingState.PLANNED))

        assertEquals(
            AbandonUndo.BeforeMark(existed = true, state = ReadingState.PLANNED),
            undo.recall(BOOK_ID)
        )
    }

    @Test
    fun `a pass the mark created is remembered as absent`() {
        undo.remember(BOOK_ID, AbandonUndo.BeforeMark(existed = false, state = null))

        assertEquals(AbandonUndo.BeforeMark(existed = false, state = null), undo.recall(BOOK_ID))
    }

    @Test
    fun `a consumed note answers nothing`() {
        undo.remember(BOOK_ID, AbandonUndo.BeforeMark(existed = true, state = ReadingState.IN_PROGRESS))
        undo.forget(BOOK_ID)

        assertNull(undo.recall(BOOK_ID))
    }

    @Test
    fun `an unknown state in the note is not trusted`() {
        writeRaw("1|DROPPED")

        assertNull("no note is better than a guessed restore", undo.recall(BOOK_ID))
    }

    @Test
    fun `a note with no state where one is due is not trusted`() {
        writeRaw("1|")

        assertNull(undo.recall(BOOK_ID))
    }

    @Test
    fun `a malformed note is not trusted`() {
        writeRaw("nonsense")

        assertNull(undo.recall(BOOK_ID))
    }

    private companion object {
        const val BOOK_ID = "entry-1"
    }
}
