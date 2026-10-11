/**
 * #1207 — the web's ONE adaptive line, and the only place the number is written.
 *
 * The application shipped as a single 640 px column (`theme.css`): right on a
 * phone, and on anything wider a handset held up in the middle of the screen.
 * Issue #1207's owner decision (2026-10-10) scopes the change to width and
 * grid; the navigation rail and the two panes of the shared map
 * (`docs/specs/2026-09-16-app-information-architecture.md` §11 step 4) stay
 * out. This module answers the one question that remains: is the window
 * compact or expanded.
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
import { useSyncExternalStore } from 'react'

/** The two layouts the web draws — Android's COMPACT/EXPANDED, same line. */
export type AppLayout = 'compact' | 'expanded'

/** Android's `ExpandedMinWidthDp` verbatim: window width, not a container's. */
export const EXPANDED_MIN_WIDTH_PX = 600

/**
 * The pure rule, so that the boundary itself is a test: 599 px is compact,
 * 600 px is not. One answer per window serves the whole app, the same way
 * Android's `rememberWindowLayout()` reads the configuration once.
 */
export function layoutForWidth(widthPx: number): AppLayout {
  return widthPx >= EXPANDED_MIN_WIDTH_PX ? 'expanded' : 'compact'
}

/** The window's layout right now. */
export function getAppLayout(): AppLayout {
  return layoutForWidth(window.innerWidth)
}

/**
 * A resize across the boundary swaps the layout without a reload — the same
 * promise Android makes for its line (`rememberWindowLayout` follows the
 * configuration change). `resize` is the whole notification: `innerWidth`
 * above is read fresh on every render, so no second evaluation path exists.
 */
export function subscribeAppLayout(onChange: () => void): () => void {
  window.addEventListener('resize', onChange)
  return () => window.removeEventListener('resize', onChange)
}

/** The layout the app root renders with. */
export function useAppLayout(): AppLayout {
  return useSyncExternalStore(subscribeAppLayout, getAppLayout)
}
