// @vitest-environment jsdom
/**
 * #1207/#1236 — the web's adaptive lines are 600 px (rail + panes) and
 * 840 px (player pane + leading mini-player), tested on both sides.
 *
 * The seam has two halves and they are pinned differently, because vitest can
 * only honestly hold one of them:
 *
 * - WHICH layout is on is decided in `adaptiveLayout.ts`, and that half is
 *   behavioural: the pure rule is read at 599/600, and the app root is rendered
 *   and watched flipping across the line on a real `resize`. jsdom runs no CSS
 *   layout, so nothing here claims to measure a column.
 * - What `expanded` MEANS is `theme.css`, and its half is pinned for what a
 *   text check can hold: the app column takes its width from the token, the
 *   expanded block carries the numbers the layout is built from (the list
 *   column, the gutter, and the cap as arithmetic over both), all four list
 *   kinds become auto-fill columns, a BookRow grid draws its closing hairline,
 *   and the rails wrap. The old fixed 640 px column and any media query — the
 *   second spelling of the boundary — cannot come back unnoticed. The visual
 *   delta itself was verified in a real browser against the built bundle,
 *   which cannot run in this job (CI's web job is `npm ci` + `tsc --noEmit` +
 *   `vitest run`).
 *
 * The ANDROID half is read as well (#1223): `EXPANDED_MIN_WIDTH_PX` and Kotlin's
 * `ExpandedMinWidthDp` are one number for two clients, so the guard below opens
 * `WindowLayout.kt` and compares the pair. The CI web job checks out the whole
 * repository, so the Kotlin file costs this lane nothing to read — and the
 * boundary stays written in exactly two places, both of them read here rather
 * than restated as a third literal.
 */
import "fake-indexeddb/auto";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { act, cleanup, render } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { App } from "../App";
import type { ListenerProfile } from "../identity/listenerIdentity";
import { setUiLocale } from "../i18n/locale";
import {
  EXPANDED_MIN_WIDTH_PX,
  hasRoomForPlayerPane,
  layoutForWidth,
  PLAYER_PANE_MIN_WIDTH_PX,
  showsLeadingMiniPlayer,
  showsPlayerPane,
} from "./adaptiveLayout";

const profile: ListenerProfile = { uid: "local-abc123", nickname: "Слухач-1" };

/** The window moves the way a browser moves it: the width, then `resize`. */
function setViewportWidth(widthPx: number): void {
  Object.defineProperty(window, "innerWidth", {
    configurable: true,
    writable: true,
    value: widthPx,
  });
  act(() => {
    window.dispatchEvent(new Event("resize"));
  });
}

function appLayout(container: HTMLElement): string | null {
  return container.querySelector(".app")?.getAttribute("data-layout") ?? null;
}

/** The Android half of the one line: `ExpandedMinWidthDp` lives in this file. */
const KOTLIN_WINDOW_LAYOUT = join(
  dirname(fileURLToPath(import.meta.url)),
  "..",
  "..",
  "..",
  "app/src/main/java/com/slukhayka/audiobooks/ui/adaptive/WindowLayout.kt"
);

/**
 * `ExpandedMinWidthDp` out of `WindowLayout.kt` (#1223).
 *
 * WHY these two clients are bound at all — the reason this test exists rather
 * than a nice-to-have: ADR-0034 makes the web client a 1:1 rebuild of the
 * Android one, «a listener switching devices cannot tell which surface they are
 * on». A width line that exists in two clients but is only TESTED in one is
 * precisely where that promise dies quietly: the Android lane could move its
 * boundary, both sides would still be green, and web would keep drawing the map
 * Android no longer draws — the divergence the shared map exists to prevent,
 * and the same class of defect #1206 charged a whole PR for. The number is
 * therefore READ from its Kotlin source, not restated here: a third literal
 * would be a copy that no test compares to either side.
 *
 * The web half is read by IMPORT in the test below — the value the bundle
 * actually ships, rather than a text match that a refactor could leave green.
 * No JavaScript module can import Kotlin, so that half is read as text.
 *
 * A moved file or a renamed constant must be a RED test, never a quiet green
 * one: this guard is worth nothing on the day it stops finding both numbers.
 */
function androidExpandedMinWidthDp(): number {
  let source: string;
  try {
    source = readFileSync(KOTLIN_WINDOW_LAYOUT, "utf8");
  } catch {
    throw new Error(
      `джерело не знайдено: ${KOTLIN_WINDOW_LAYOUT} — якщо файл переїхав, ` +
        "оновіть шлях тут, інакше спільну межу Android/Web ніхто не звіряє"
    );
  }
  // Comments are stripped before the match, the same rule the CSS half of this
  // file applies: a comment must never be able to satisfy a pin. Without it,
  // `// const val ExpandedMinWidthDp: Int = 600` over a live `601` read as 600
  // and kept the suite green (PR #1231 review) — the quiet green this guard
  // exists to prevent. The match is ALSO anchored to a line start, so a
  // commented declaration cannot pass for the live one whatever shape the
  // comment takes; underscores are legal in a Kotlin Int literal.
  const code = source
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .replace(/\/\/[^\n]*/g, "");
  const declaration =
    /^\s*const\s+val\s+ExpandedMinWidthDp\s*(?::\s*Int)?\s*=\s*(\d[\d_]*)/m.exec(
      code
    );
  if (declaration === null) {
    throw new Error(
      `константу не знайдено: ExpandedMinWidthDp у ${KOTLIN_WINDOW_LAYOUT} — ` +
        "перейменована чи переписана? оновіть цей тест, а не видаляйте його"
    );
  }
  return Number(declaration[1]!.replace(/_/g, ""));
}

afterEach(() => {
  cleanup();
  window.localStorage.clear();
  setUiLocale("uk");
});

describe("#1207 the adaptive line", () => {
  it("is Android’s ExpandedMinWidthDp, read from the Kotlin source (#1223)", () => {
    // Not `toBe(600)`: the literal would be a third copy nothing compares to
    // either client. Both halves are read — see `androidExpandedMinWidthDp`.
    expect(androidExpandedMinWidthDp()).toBe(EXPANDED_MIN_WIDTH_PX);
  });

  it("calls 599 px a phone and 600 px not one", () => {
    // The sides Android pins in WindowLayoutTest, at the same numbers.
    for (const phone of [0, 320, 360, 411, 480, 599]) {
      expect(layoutForWidth(phone)).toBe("compact");
    }
    for (const wide of [600, 601, 840, 1024, 1440, 2560]) {
      expect(layoutForWidth(wide)).toBe("expanded");
    }
  });

  it("keeps the player pane on the shared 840 px line, with no height condition", () => {
    // #1205/#1208: 839 is the full-screen player, 840 is the right-hand pane;
    // the answer depends on width and the player's own state, never height.
    expect(PLAYER_PANE_MIN_WIDTH_PX).toBe(840);
    for (const narrow of [0, 411, 599, 600, 839]) {
      expect(hasRoomForPlayerPane(narrow)).toBe(false);
      expect(showsPlayerPane(narrow, true)).toBe(false);
      expect(showsLeadingMiniPlayer(narrow, true)).toBe(false);
    }
    for (const wide of [840, 841, 1024, 2560]) {
      expect(hasRoomForPlayerPane(wide)).toBe(true);
      expect(showsPlayerPane(wide, true)).toBe(true);
      expect(showsLeadingMiniPlayer(wide, true)).toBe(true);
    }
    // A wide window with nothing to show draws no pane and no empty column.
    expect(showsPlayerPane(1024, false)).toBe(false);
    expect(showsLeadingMiniPlayer(1024, false)).toBe(false);
  });

  it("writes the answer on the app root and flips it live across the line", () => {
    setUiLocale("uk");
    // A quiet tab: this test is about the root's layout attribute, not about
    // what the Огляд fetches while it renders.
    window.localStorage.setItem("slukhayka.selected_tab", "settings");

    setViewportWidth(EXPANDED_MIN_WIDTH_PX - 1);
    const { container } = render(<App profile={profile} />);
    expect(appLayout(container)).toBe("compact");

    setViewportWidth(EXPANDED_MIN_WIDTH_PX);
    expect(appLayout(container)).toBe("expanded");

    // Back across the line: a window that shrinks again is a phone again.
    setViewportWidth(EXPANDED_MIN_WIDTH_PX - 1);
    expect(appLayout(container)).toBe("compact");
  });
});

describe("#1207 theme.css half of the seam", () => {
  // `fileURLToPath(import.meta.url)`, not `new URL(…, import.meta.url)`: under
  // the jsdom environment the global `URL` resolves a relative path against
  // the document's http://localhost origin instead of the module's file URL.
  const source = readFileSync(
    join(dirname(fileURLToPath(import.meta.url)), "..", "theme.css"),
    "utf8"
  );
  // Rules only: a comment must never be able to satisfy a pin (the seam block
  // above, for one, talks ABOUT `@media` while the sheet has none).
  const css = source.replace(/\/\*[\s\S]*?\*\//g, "");

  /** Every `selectors { declarations }` rule of the sheet, in file order. */
  const rules = [...css.matchAll(/([^{}]+)\{([^{}]*)\}/g)].map((match) => ({
    selectors: match[1]!.trim(),
    declarations: match[2]!.trim(),
  }));
  const wide = rules.filter((rule) =>
    rule.selectors.includes(".app[data-layout='expanded']")
  );
  /** The token block: the only wide rule whose selector is the attribute alone. */
  const numbers =
    wide.find((rule) => rule.selectors.endsWith(".app[data-layout='expanded']"))
      ?.declarations ?? "";
  /** A BookRow list of real rows that is safe for a grid — the long selector. */
  const bookRowList =
    ".app[data-layout='expanded'] .surface .card-list:not(:has(> :not(.bookrow))):not(:empty)";

  it("takes the app column width from the token, never from a literal", () => {
    expect(css).toContain("max-width: var(--layout-max-width)");
    // The old fixed column: `#root { … max-width: 640px }` must not return.
    expect(css).not.toMatch(/#root\s*\{[^}]*max-width/);
  });

  it("keeps every number the wide layout reads in the expanded block", () => {
    expect(numbers).toContain("--list-column-min: 320px");
    expect(numbers).toContain("--list-column-gap: 16px");
    expect(numbers).toContain("--layout-gutter: 24px");
    // The cap is three columns plus their gaps and gutters, not a literal that
    // could drift from the grid it holds (the lesson of #1206, one layer up).
    const cap =
      /--layout-max-width:\s*calc\(([^;]*)\)/.exec(numbers)?.[1] ?? "";
    for (const token of [
      "--list-column-min",
      "--list-column-gap",
      "--layout-gutter",
    ]) {
      expect(cap).toContain(`var(${token})`);
    }
  });

  it("turns all four list kinds into auto-fill columns of the column token", () => {
    const grid = wide.find((rule) =>
      rule.declarations.includes("grid-template-columns")
    );
    expect(grid).toBeDefined();
    for (const selector of [
      bookRowList,
      ".app[data-layout='expanded'] .surface .narrations",
      ".app[data-layout='expanded'] .surface .chapters",
      ".app[data-layout='expanded'] .surface .collection-list",
    ]) {
      expect(grid?.selectors).toContain(selector);
    }
    expect(grid?.declarations).toContain(
      "grid-template-columns: repeat(auto-fill, minmax(var(--list-column-min), 1fr))"
    );
    expect(grid?.declarations).toContain("column-gap: var(--list-column-gap)");
  });

  it("draws the closing hairline of a BookRow grid, and only over rows that exist", () => {
    const hairline = wide.find((rule) =>
      rule.declarations.includes("row-gap: 0")
    );
    expect(hairline?.selectors).toContain(bookRowList);
    expect(hairline?.declarations).toContain(
      "border-top: 1px solid var(--line)"
    );
    // …and the line `:first-child` draws alone while there is one column gives
    // way to the grid's own top edge.
    const firstRow = wide.find((rule) =>
      rule.selectors.includes("> .bookrow:first-child")
    );
    expect(firstRow?.declarations).toContain("border-top: none");
  });

  it("lets the rails wrap instead of hiding cards behind a scroll", () => {
    const rails = wide.find((rule) =>
      rule.declarations.includes("flex-wrap: wrap")
    );
    for (const selector of [
      ".app[data-layout='expanded'] .surface .shelf-rail",
      ".app[data-layout='expanded'] .surface .poster-row",
      ".app[data-layout='expanded'] .surface .collection-rail-row",
    ]) {
      expect(rails?.selectors).toContain(selector);
    }
    expect(rails?.declarations).toContain("overflow: visible");
  });

  it("draws the rail and the list-detail panes from the same layout attribute", () => {
    expect(css).toContain(".app[data-layout='expanded'] .nav-rail");
    expect(css).toContain(".app[data-layout='expanded'] .list-detail");
    expect(css).toContain(".app[data-layout='expanded'] .list-pane");
    expect(css).toContain(".app[data-layout='expanded'] .detail-pane");
    // The 840 px player line is READ from adaptiveLayout.ts; a CSS copy would
    // be a second spelling of it, exactly like the old @media copy below.
    expect(css).not.toContain(`${PLAYER_PANE_MIN_WIDTH_PX}px`);
  });

  it("keeps the boundary out of the stylesheet, where it would exist twice", () => {
    // No media query at all: the file has none, and any width key here would
    // be the second spelling of the line `adaptiveLayout.ts` owns. The key is
    // asked for by VALUE, so a moved boundary moves this pin with it instead of
    // leaving a stale `600px` that a fresh `620px` could satisfy.
    expect(css).not.toMatch(/@media/);
    expect(css).not.toContain(`${EXPANDED_MIN_WIDTH_PX}px`);
  });
});
