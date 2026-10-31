package com.slukhayka.audiobooks.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.slukhayka.audiobooks.ui.theme.PaperInkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1703 — the undo half of «Не цікаво».
 *
 * `ListenPrefsStore.undismissBook` had no UI caller, so a dismissal (or a miss
 * on the old 32 dp target) was permanent. [ListenDismissUndo] pairs every
 * dismiss with ONE snackbar; this test proves the pairing: dismissing writes
 * the work away immediately, the snackbar appears, and ONLY its
 * «Повернути» action restores that same work id.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ListenDismissUndoTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the undo action restores exactly the dismissed work`() {
        var dismissed: String? = null
        var undone: String? = null
        compose.setContent {
            val host = remember { SnackbarHostState() }
            val scope = rememberCoroutineScope()
            val undo = remember(host, scope) {
                ListenDismissUndo(
                    snackbarHostState = host,
                    scope = scope,
                    message = "Приховано",
                    actionLabel = "Повернути",
                    onDismiss = { dismissed = it },
                    onUndo = { undone = it },
                )
            }
            PaperInkTheme(darkTheme = true) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Button(onClick = { undo.dismiss("book-1") }) { Text("dismiss") }
                    SnackbarHost(
                        hostState = host,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }

        compose.onNodeWithText("dismiss").performClick()
        compose.onNodeWithText("Повернути").assertIsDisplayed()
        assertEquals("the dismiss is written immediately", "book-1", dismissed)
        assertNull("the undo must not fire before its action", undone)

        compose.onNodeWithText("Повернути").performClick()
        assertEquals("the action restores the same work", "book-1", undone)
    }
}
