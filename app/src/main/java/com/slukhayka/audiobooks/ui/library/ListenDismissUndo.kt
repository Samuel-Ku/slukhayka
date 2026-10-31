package com.slukhayka.audiobooks.ui.library

import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * #1703 — «Не цікаво» is reversible from the place it was chosen.
 *
 * [ListenPrefsStore.undismissBook] existed since wayfinder #62, but no UI
 * called it: one tap (or one miss on the old 32 dp target) hid a Work from
 * every «Слухати» shelf until the process data was cleared. This holder pairs
 * every dismiss with ONE quiet snackbar whose only action restores that same
 * Work; the snackbar is indefinite (Material's contract for an action label),
 * so the repair does not depend on a reaction within a few seconds.
 *
 * A holder instead of an inline `scope.launch` because the pairing is the
 * behaviour worth testing: [dismiss] must both write the preference and offer
 * the undo, and only [SnackbarResult.ActionPerformed] may undo it.
 */
class ListenDismissUndo(
    private val snackbarHostState: SnackbarHostState,
    private val scope: CoroutineScope,
    private val message: String,
    private val actionLabel: String,
    private val onDismiss: (String) -> Unit,
    private val onUndo: (String) -> Unit,
) {
    /** Hides [bookId], then offers «Повернути» for exactly that Work. */
    fun dismiss(bookId: String) {
        onDismiss(bookId)
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = actionLabel,
                withDismissAction = true,
            )
            if (result == SnackbarResult.ActionPerformed) {
                onUndo(bookId)
            }
        }
    }
}
