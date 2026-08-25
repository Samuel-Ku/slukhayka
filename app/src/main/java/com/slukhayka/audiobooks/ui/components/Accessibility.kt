package com.slukhayka.audiobooks.ui.components

import androidx.compose.ui.Modifier
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
