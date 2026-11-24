/**
 * #1207/#1236 — the web's adaptive lines, and the only place the numbers are
 * written.
 *
 * The application shipped as a single 640 px column (`theme.css`): right on a
 * phone, and on anything wider a handset held up in the middle of the screen.
 * #1207 delivered the first half (adaptive width and grid from 600 px); #1236
 * adds the rest of the shared map (`docs/specs/2026-09-16-app-information-
 * architecture.md` §11 step 4): the rail at 600 px and the player's right-hand
 * pane at 840 px, with no height condition. This module owns both numbers; the
 * stylesheet reads `data-layout` and never spells a breakpoint itself.
 *
 * The number is Android's, not a web invention: `ExpandedMinWidthDp = 600` in
 * `app/src/main/java/com/slukhayka/audiobooks/ui/adaptive/WindowLayout.kt` —
 * the compact/medium line of Material, and the boundary the Android side pins
 * in `WindowLayoutTest` (599 dp is a phone, 600 dp is not). Both clients draw
 * the same map (ADR-0034), CSS px and dp are the same unit at the reference
 * density, and a second, web-only breakpoint would be exactly the divergence
 * the shared map exists to prevent.
 *
 * `App` writes the answer on its root element as `data-layout`, and
 * `theme.css` holds everything the expanded layout changes under that single
 * attribute selector. There is deliberately no `@media` in the CSS: a media
 * query would be the SECOND copy of the number, and two copies drift — the
 * Android lane paid for that lesson in #1206, where a grid's column count and
 * its floor had to be pulled back onto the one rule that owns them.
 *
 * The window's raw width decides, not `matchMedia('(min-width: …)')`: the
 * answer then comes from `layoutForWidth()` — the very function the test pins
 * on both sides — instead of from a query string that would only spell the
 * same number a second time. (`matchMedia` also does not exist in jsdom at
 * all, so the seam would need a fake media-query engine in the test setup
 * before it could be pinned anywhere.)
 */
import { useSyncExternalStore } from "react";

/** The two layouts the web draws — Android's COMPACT/EXPANDED, same line. */
export type AppLayout = "compact" | "expanded";

/** Android's `ExpandedMinWidthDp` verbatim: window width, not a container's. */
export const EXPANDED_MIN_WIDTH_PX = 600;

/**
 * The pure rule, so that the boundary itself is a test: 599 px is compact,
 * 600 px is not. One answer per window serves the whole app, the same way
 * Android's `rememberWindowLayout()` reads the configuration once.
 */
export function layoutForWidth(widthPx: number): AppLayout {
  return widthPx >= EXPANDED_MIN_WIDTH_PX ? "expanded" : "compact";
}

/** The window's layout right now. */
export function getAppLayout(): AppLayout {
  return layoutForWidth(window.innerWidth);
}

/**
 * A resize across the boundary swaps the layout without a reload — the same
 * promise Android makes for its line (`rememberWindowLayout` follows the
 * configuration change). `resize` is the whole notification: `innerWidth`
 * above is read fresh on every render, so no second evaluation path exists.
 */
export function subscribeAppLayout(onChange: () => void): () => void {
  window.addEventListener("resize", onChange);
  return () => window.removeEventListener("resize", onChange);
}

/** The window's raw width right now (#1236: the player's second line). */
export function getWindowWidth(): number {
  return window.innerWidth;
}

/** Width changes use the same one notification the layout subscription does. */
export const subscribeWindowWidth = subscribeAppLayout;

/** The window's current width, for the 840 px player line. */
export function useWindowWidth(): number {
  return useSyncExternalStore(subscribeWindowWidth, getWindowWidth);
}

/** The layout the app root renders with. */
export function useAppLayout(): AppLayout {
  return useSyncExternalStore(subscribeAppLayout, getAppLayout);
}

/**
 * #1236 — the SECOND width line of the shared map: from here the player has
 * room to stand BESIDE the content it was opened from instead of covering it.
 *
 * The number is Android's `PlayerPaneMinWidthDp` (ui/adaptive/WindowLayout.kt),
 * not a web invention: ADR-0034 draws one map for both clients, and a second
 * web-only line would be exactly the divergence that map exists to prevent.
 * Everything below `EXPANDED_MIN_WIDTH_PX` stays compact, 600–839 stays
 * expanded without the player pane, and only the width decides — there is
 * deliberately NO height condition (owner's decision in #1205/#1208).
 */
export const PLAYER_PANE_MIN_WIDTH_PX = 840;

/** Is this window wide enough for the player's own right-hand column? */
export function hasRoomForPlayerPane(widthPx: number): boolean {
  return widthPx >= PLAYER_PANE_MIN_WIDTH_PX;
}

/**
 * Does an OPEN player take the right-hand pane (true) or the full-screen
 * overlay it has always been (false)? The player's own state is an input: a
 * wide window with nothing open has no pane to draw.
 */
export function showsPlayerPane(widthPx: number, playerOpen: boolean): boolean {
  return playerOpen && hasRoomForPlayerPane(widthPx);
}

/**
 * Does the mini-player live in the leading column, under the rail, instead of
 * the bottom strip? The same 840 px line: the column has to hold the bar, and
 * at 600–839 taking that width would squeeze the list the 600 px line was
 * drawn for. A visible bar is an input for the same reason as the player's
 * open state — the column must not widen for a bar that draws nothing.
 */
export function showsLeadingMiniPlayer(
  widthPx: number,
  miniPlayerVisible: boolean
): boolean {
  return miniPlayerVisible && hasRoomForPlayerPane(widthPx);
}
