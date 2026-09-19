package com.slukhayka.audiobooks.ui.adaptive

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import com.slukhayka.audiobooks.ui.SelectedTab

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

/**
 * #900 — should this root render its list with the opened item's page BESIDE
 * it, instead of the phone's one-screen-at-a-time push?
 *
 * Three inputs, one answer, no composition: the window is wide, the open book
 * belongs to a root that has a page to keep beside its list, and there IS an
 * open book. The phone path is untouched — a false here means the existing
 * full-screen route renders exactly as before.
 *
 * Бібліотека is the root the owner named for the library slice; Огляд has its
 * own answer right below ([showsWideExploreDetailPane]) because its trigger is
 * a pushed parent list, not a root-level selection.
 */
fun showsWideDetailPane(
    layout: WindowLayout,
    tab: SelectedTab,
    detailOpen: Boolean
): Boolean = layout == WindowLayout.EXPANDED && detailOpen && tab == SelectedTab.LIBRARY

/**
 * #900 — the same question for «Огляд»: does the list the listener opened the
 * work FROM stay beside the work's card?
 *
 * [hasParentList] is true only when the open book came from one of the two
 * surfaces the owner named — a genre page or a series page («добірка/жанр»).
 * The rating (TOP_100) and person lists keep the phone route for now: the
 * issue does not say whether they belong here, and this slice does not guess
 * (the question is recorded on #900).
 */
fun showsWideExploreDetailPane(
    layout: WindowLayout,
    tab: SelectedTab,
    hasParentList: Boolean
): Boolean = layout == WindowLayout.EXPANDED && hasParentList && tab == SelectedTab.EXPLORE

/**
 * #962 — the height below which an EXPANDED window is a LANDSCAPE PHONE, not a
 * tablet.
 *
 * #900 put the line at width alone, which is right for «rail instead of the
 * bottom bar» but blind to the second dimension: a phone turned sideways is
 * 905 dp WIDE and only 411 dp TALL (OnePlus 8 Pro, 3168×1440 @560 dpi,
 * measured on device). At that width every rule that widens chrome fires, and
 * the 411 dp of height then has to pay for all of it — the Library's header,
 * year hero, tabs, search field and chip row alone measured 1402 px of the
 * 1440 px, leaving the book grid ≈11 dp and no scroll to reach it (#962).
 *
 * So the width line still decides the ADAPTIVE layout, and this second line
 * only decides how much CHROME that layout may spend. It sits at 600 dp for
 * the same reason [ExpandedMinWidthDp] does: 600 dp is the shortest height at
 * which the wide chrome (hero + search + chips + a real list) still fits, and
 * a portrait tablet at 840×1000 dp stays well above it. A landscape phone is
 * the only window that crosses the width line while staying under this one.
 */
const val WideChromeMinHeightDp: Int = 600

/**
 * #962 — may this window afford the FULL wide chrome?
 *
 * True for every window that already was (portrait phones and tablets never
 * change), true for a wide-and-tall tablet, and false only for the wide AND
 * short window a landscape phone produces. Pure, like [windowLayoutFor], so
 * the boundary is a JVM test rather than something only a device can answer.
 */
fun showsWideChrome(widthDp: Int, heightDp: Int): Boolean =
    windowLayoutFor(widthDp) == WindowLayout.EXPANDED && heightDp >= WideChromeMinHeightDp

/**
 * #962 — is this the LANDSCAPE-PHONE window: wide enough for every EXPANDED
 * rule, and too short to pay for them?
 *
 * This is the one shape that needs the compact chrome, and stating it as its
 * own predicate keeps the call sites honest. It is NOT `!showsWideChrome(...)`:
 * that is also true of a narrow portrait window (the 320 × 470 dp Robolectric
 * default, a split-screen half), which must keep the ordinary phone layout it
 * has always had. A window qualifies only if it is EXPANDED by width AND
 * shorter than [WideChromeMinHeightDp].
 */
fun isLandscapePhoneWindow(widthDp: Int, heightDp: Int): Boolean =
    windowLayoutFor(widthDp) == WindowLayout.EXPANDED && heightDp < WideChromeMinHeightDp

/**
 * #962 — the CURRENT window's chrome budget, the height twin of
 * [rememberWindowLayout].
 *
 * Reads the same [LocalConfiguration] (the Activity's own window, so a
 * split-screen half or a folded foldable answers for itself) and follows a
 * resize without a restart.
 */
@Composable
fun rememberShowsWideChrome(): Boolean = LocalConfiguration.current.let {
    showsWideChrome(it.screenWidthDp, it.screenHeightDp)
}

/** #962 — the CURRENT window as a landscape phone, for the compact layouts. */
@Composable
fun rememberIsLandscapePhoneWindow(): Boolean = LocalConfiguration.current.let {
    isLandscapePhoneWindow(it.screenWidthDp, it.screenHeightDp)
}


