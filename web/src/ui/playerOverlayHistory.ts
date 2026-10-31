/**
 * #1688 — the history entry the full-screen player overlay owns, so ONE system
 * Back closes the player instead of leaving the app. The marker lives in its
 * own module because two owners read it: `App` (pushes/pops it around
 * `playerOpen`) and the overlay (declares the marker type). A foreign entry is
 * never interpreted as ours.
 */
export type PlayerOverlayHistoryState = { readonly dshPlayerOverlay: true };

export const PLAYER_OVERLAY_MARKER: PlayerOverlayHistoryState = {
  dshPlayerOverlay: true,
};

export function isPlayerOverlayState(
  state: unknown
): state is PlayerOverlayHistoryState {
  return (
    typeof state === "object" &&
    state !== null &&
    (state as { dshPlayerOverlay?: unknown }).dshPlayerOverlay === true
  );
}
