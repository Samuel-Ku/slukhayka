package com.slukhayka.audiobooks.ui.components

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
 * previously focused descendant for the modal owner's close path unless that
 * owner supplies a more precise explicit restoration contract.
 */
fun Modifier.accessibilityModalBackground(
    modalVisible: Boolean,
    automaticFocusRestoration: Boolean = true
): Modifier =
    then(if (automaticFocusRestoration) Modifier.focusRestorer() else Modifier).then(
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
 * [settleFrames] lets animated owners finish removing their focused subtree
 * before the return request; non-animated owners keep the one-frame default.
 * [stabilityFrames] optionally reasserts that focus after late layout changes;
 * the restoration is consumed only when that final request succeeds.
 */
@Composable
fun RestoreFocusAfterModal(
    modalVisible: Boolean,
    returnFocusRequester: FocusRequester?,
    fallbackFocusRequester: FocusRequester? = null,
    settleFrames: Int = 1,
    stabilityFrames: Int = 0,
    onFocusRestored: () -> Unit = {}
) {
    var modalWasVisible by remember { mutableStateOf(false) }
    LaunchedEffect(modalVisible, returnFocusRequester) {
        if (modalVisible) {
            modalWasVisible = true
        } else if (modalWasVisible) {
            if (returnFocusRequester == null) {
                modalWasVisible = false
            } else {
                repeat(settleFrames.coerceAtLeast(1)) { withFrameNanos { } }
                val initiallyRestoredToOrigin = runCatching {
                    returnFocusRequester.requestFocus()
                }.getOrDefault(false) || run {
                    // A disappearing modal and its launcher can detach in the
                    // same frame. Give layout one bounded retry before using a
                    // stable screen-level fallback.
                    withFrameNanos { }
                    runCatching { returnFocusRequester.requestFocus() }
                        .getOrDefault(false)
                }
                val restoredToOrigin = if (
                    initiallyRestoredToOrigin && stabilityFrames > 0
                ) {
                    repeat(stabilityFrames) { withFrameNanos { } }
                    // Animated and lazy layouts can still detach the focused
                    // node shortly after the first successful request. A final
                    // request keeps the restoration intent alive through that
                    // bounded stabilization window.
                    runCatching { returnFocusRequester.requestFocus() }
                        .getOrDefault(false)
                } else {
                    initiallyRestoredToOrigin
                }
                val restored = restoredToOrigin || fallbackFocusRequester?.let { fallback ->
                    runCatching { fallback.requestFocus() }.getOrDefault(false)
                } == true
                if (restored) {
                    modalWasVisible = false
                    onFocusRestored()
                }
            }
        }
    }
}
