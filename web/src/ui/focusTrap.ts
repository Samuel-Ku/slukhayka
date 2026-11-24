/**
 * #1688/#1697 — keep Tab / Shift+Tab inside a modal surface. The web app's
 * dialogs are rendered as siblings of the page (no browser-native `<dialog>`),
 * so the trap is explicit: from the last control forward wraps to the first,
 * from the first backwards wraps to the last, and focus that is outside the
 * surface is pulled back in.
 */
export function trapFocus(
  container: HTMLElement | null,
  event: KeyboardEvent
): void {
  if (container === null) return;
  const focusable = Array.from(
    container.querySelectorAll<HTMLElement>(
      'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])'
    )
  ).filter((element) => !element.hasAttribute("disabled"));
  if (focusable.length === 0) {
    event.preventDefault();
    return;
  }
  const first = focusable[0];
  const last = focusable[focusable.length - 1];
  const active = document.activeElement;
  const outside = active === null || !container.contains(active);
  if (event.shiftKey && (active === first || outside)) {
    event.preventDefault();
    last.focus();
  } else if (!event.shiftKey && (active === last || outside)) {
    event.preventDefault();
    first.focus();
  }
}
