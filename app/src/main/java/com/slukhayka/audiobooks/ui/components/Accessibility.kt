package com.slukhayka.audiobooks.ui.components

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics

/**
 * Keeps a composed background out of accessibility and keyboard traversal
 * while a sibling modal surface is visible. [focusRestorer] remembers the
 * previously focused descendant for the modal owner's close path.
 */
fun Modifier.accessibilityModalBackground(modalVisible: Boolean): Modifier =
    focusRestorer().then(
        if (modalVisible) {
            Modifier
                .focusProperties { canFocus = false }
                .semantics { hideFromAccessibility() }
        } else {
            Modifier
        }
    )

/** Accessibility announcement contract for pushed and modal surfaces. */
fun Modifier.accessibilityPane(title: String): Modifier =
    semantics { paneTitle = title }

/**
 * Returns focus to the control that opened a modal after that modal has left
 * composition. An initially closed modal is deliberately a no-op: focus only
 * moves after this owner has observed one visible-to-closed transition.
 */
@Composable
fun RestoreFocusAfterModal(
    modalVisible: Boolean,
    returnFocusRequester: FocusRequester?,
    fallbackFocusRequester: FocusRequester? = null,
    onFocusRestored: () -> Unit = {}
) {
    var modalWasVisible by remember { mutableStateOf(false) }
    LaunchedEffect(modalVisible, returnFocusRequester) {
        Log.d("FocusRestoreProbe", "effect visible=$modalVisible requester=${returnFocusRequester != null} wasVisible=$modalWasVisible")
        if (modalVisible) {
            modalWasVisible = true
        } else if (modalWasVisible) {
            if (returnFocusRequester == null) {
                modalWasVisible = false
            } else {
                withFrameNanos { }
                val restoredToOrigin = runCatching {
                    returnFocusRequester.requestFocus()
                }.getOrDefault(false) || run {
                    // A disappearing modal and its launcher can detach in the
                    // same frame. Give layout one bounded retry before using a
                    // stable screen-level fallback.
                    withFrameNanos { }
                    runCatching { returnFocusRequester.requestFocus() }
                        .getOrDefault(false)
                }
                val restored = restoredToOrigin || fallbackFocusRequester?.let { fallback ->
                    runCatching { fallback.requestFocus() }.getOrDefault(false)
                } == true
                Log.d("FocusRestoreProbe", "attempt origin=$restoredToOrigin restored=$restored")
                if (restored) {
                    modalWasVisible = false
                    onFocusRestored()
                }
            }
        }
    }
}
