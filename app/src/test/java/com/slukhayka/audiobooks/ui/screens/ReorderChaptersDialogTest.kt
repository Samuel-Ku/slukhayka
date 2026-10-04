package com.slukhayka.audiobooks.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsNotEnabled
import com.slukhayka.audiobooks.ui.components.ChapterOrderDialog
import com.slukhayka.audiobooks.data.db.ChapterEntity
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1049 — the reorder dialog's own contract.
 *
 * The device check found no test covering it, so this closes that gap. What
 * matters here is NOT that arrows move rows — it is that the dialog hands back
 * a PERMUTATION of the chapters it was given, and that dismissing writes
 * nothing. A dialog that dropped or duplicated a chapter would silently lose
 * the listener's audio.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReorderChaptersDialogTest {

    @get:Rule
    val compose = createComposeRule()

    private fun chapters() = listOf(
        ChapterEntity(id = "ch-1", bookId = "b", chapterIndex = 0, title = "Перший", durationSeconds = 10, editionId = "e"),
        ChapterEntity(id = "ch-2", bookId = "b", chapterIndex = 1, title = "Другий", durationSeconds = 10, editionId = "e"),
        ChapterEntity(id = "ch-3", bookId = "b", chapterIndex = 2, title = "Третій", durationSeconds = 10, editionId = "e")
    )

    @Test
    fun `confirming returns a permutation with the moved chapter in its new place`() {
        var confirmed: List<String>? = null
        compose.setContent {
            AudiobookTheme {
                ChapterOrderDialog(
                    chapters = chapters(),
                    saving = false,
                    errorMessage = null,
                    onDismiss = {},
                    onSave = { confirmed = it }
                )
            }
        }

        // Move the LAST chapter one step up: [ch-1, ch-3, ch-2].
        compose.onNodeWithTag("chapter_order_up_ch-3").performClick()
        compose.onNodeWithTag("chapter_order_save").performClick()

        assertEquals(listOf("ch-1", "ch-3", "ch-2"), confirmed)
    }

    @Test
    fun `an unchanged draft cannot write a redundant order`() {
        var confirmed: List<String>? = null
        compose.setContent {
            AudiobookTheme {
                ChapterOrderDialog(
                    chapters = chapters(),
                    saving = false,
                    errorMessage = null,
                    onDismiss = {},
                    onSave = { confirmed = it }
                )
            }
        }

        compose.onNodeWithTag("chapter_order_save").assertIsNotEnabled()
        assertEquals(null, confirmed)
    }

    @Test
    fun `dismissing writes nothing`() {
        var confirmed: List<String>? = null
        var dismissed = false
        compose.setContent {
            AudiobookTheme {
                ChapterOrderDialog(
                    chapters = chapters(),
                    saving = false,
                    errorMessage = null,
                    onDismiss = { dismissed = true },
                    onSave = { confirmed = it }
                )
            }
        }

        compose.onNodeWithTag("chapter_order_up_ch-3").performClick()
        compose.onNodeWithTag("chapter_order_cancel").performClick()

        // The dialog works on a local copy: no order may reach the caller.
        assertTrue("скасування мусить закрити діалог", dismissed)
        assertEquals("скасування не пише нічого", null, confirmed)
    }
}
