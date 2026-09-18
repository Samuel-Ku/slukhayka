package com.slukhayka.audiobooks.ui.adaptive

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

/**
 * #900 — how the app lays out for the window it is given.
 *
 * Two values, not Material's three: the owner's decision on issue #900
 * (2026-09-18) draws ONE line at 600 dp and says what sits on each side of
 * it — a rail instead of the bottom bar, and a list with its detail beside
 * it. A "medium" class nothing renders would be vocabulary without a screen.
 */
enum class WindowLayout {
    /** Phone-width window: bottom navigation, one screen at a time. */
    COMPACT,

    /** At least [ExpandedMinWidthDp]: a navigation rail, list and detail side by side. */
    EXPANDED
}

/**
 * The one number the whole adaptation turns on: 600 dp of WINDOW width.
 *
 * It is Material's compact/medium boundary and the smallest width at which a
 * list and a real book page fit side by side without either collapsing into a
 * column of truncated titles.
 */
const val ExpandedMinWidthDp: Int = 600

/**
 * The layout for a window [widthDp] wide.
 *
 * Pure on purpose: the boundary is a test (599 dp is a phone, 600 dp is not —
 * see `WindowLayoutTest`), not something only a device can answer.
 */
fun windowLayoutFor(widthDp: Int): WindowLayout =
    if (widthDp >= ExpandedMinWidthDp) WindowLayout.EXPANDED else WindowLayout.COMPACT

/**
 * The CURRENT window's layout.
 *
 * [LocalConfiguration]'s `screenWidthDp` is the Activity's own configuration,
 * so it answers for the window, not the panel: a split-screen half, a
 * multi-window pane or a folded foldable reports the width it currently has,
 * and the layout follows a resize without a restart.
 *
 * The Compose BOM in this repo ships no `material3-window-size-class` or
 * adaptive artifact, so the comparison is made on the configuration the
 * project already reads for layout decisions (the book-detail hero) instead of
 * adding a dependency for one `>=`.
 */
@Composable
fun rememberWindowLayout(): WindowLayout =
    windowLayoutFor(LocalConfiguration.current.screenWidthDp)
