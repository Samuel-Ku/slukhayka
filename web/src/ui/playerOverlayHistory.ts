/**
 * #1688 — the ONE history entry that makes a system Back close the full-screen
 * player instead of leaving the app. The App is the only owner: it pushes and
 * pops this marker around `playerOpen`. The marker and guard live in their own
 * module so the App and the overlay's tests share the SAME definition instead
 * of re-spelling the literal. A foreign entry is never interpreted as ours.
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
