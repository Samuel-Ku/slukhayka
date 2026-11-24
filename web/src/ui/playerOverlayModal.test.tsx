// @vitest-environment jsdom
/**
 * #1688 — the full-screen player is a real modal on the REAL `<App/>`:
 * one system Back / Escape closes it, focus returns to the mini-player, and a
 * stale FORWARD entry carrying the player marker never reads as a view.
 *
 * jsdom has no media pipeline, so the only thing stubbed is the "wait for the
 * real `playing` event" waiter: the real `loadBook` still runs, which is
 * exactly what makes the mini-player appear.
 */
import "fake-indexeddb/auto";
import { IDBFactory } from "fake-indexeddb";
import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "../App";
import { api } from "../api/client";
import { AudioEngine } from "../player/audioEngine";
import type { Chapter } from "../worker/types";
import { setUiLocale } from "../i18n/locale";
import type { ListenerProfile } from "../identity/listenerIdentity";
import type { BookDetail, UnifiedWorkPage } from "../worker/types";
import {
  PLAYER_OVERLAY_MARKER,
  isPlayerOverlayState,
} from "./playerOverlayHistory";

// jsdom cannot really probe media: the stream check and the range preflight are
// the only stubbed availability seams (the rest of the race stays real).
vi.mock("./catalogAvailability", async (importOriginal) => {
  const actual = await importOriginal<typeof import("./catalogAvailability")>();
  return {
    ...actual,
    probeStreamPlaying: vi.fn(async () => true),
    preflightMediaRange: vi.fn(async () => true),
  };
});

const profile: ListenerProfile = { uid: "local-abc123", nickname: "Слухач-1" };

const chapters: Chapter[] = [
  {
    title: "Розділ 1",
    streamUrl: "https://s/old-man/1.mp3",
    durationSeconds: 3600,
  },
];

const page: UnifiedWorkPage = {
  works: [
    {
      id: "w-1",
      mergeKey: "старий і море|ернест гемінґвей",
      title: "Старий і море",
      author: "Ернест Гемінґвей",
      editions: [
        {
          id: "e-1",
          narrator: "Диктор",
          language: "uk",
          durationSeconds: 3600,
          sources: [{ sourceId: "sound-books", url: "https://s/old-man" }],
        },
      ],
    },
  ],
};

const detail: BookDetail = {
  url: "https://s/old-man",
  title: "Старий і море",
  author: "Ернест Гемінґвей",
  narrator: "Диктор",
  language: "uk",
  genres: [],
  chapters,
  otherNarrations: [],
  relatedBooks: [],
};

function setViewportWidth(widthPx: number, notify = false): void {
  Object.defineProperty(window, "innerWidth", {
    configurable: true,
    writable: true,
    value: widthPx,
  });
  if (notify) {
    act(() => {
      window.dispatchEvent(new Event("resize"));
    });
  }
}

/**
 * A catalogue Play both starts the book AND opens the full-screen player
 * (`handlePlay` does `if (playing) setPlayerOpen(true)`), so the overlay is the
 * thing that appears — no separate mini-player click needed.
 */
async function openPlayerFromCatalogue(): Promise<HTMLElement> {
  const user = userEvent.setup();
  const play = await screen.findByRole("button", {
    name: /Слухати: Старий і море/,
  });
  await user.click(play);
  return screen.findByRole("dialog", { name: /Розділ 1/ }, { timeout: 3_000 });
}

/** Open a real book page first, then start a chapter: the page stays beneath. */
async function openBookPageThenOverlay(): Promise<void> {
  const user = userEvent.setup();
  await waitFor(() =>
    expect(
      screen.getAllByRole("button", {
        name: /Відкрити книгу: Старий і море/,
      }).length
    ).toBeGreaterThan(0)
  );
  await user.click(
    screen.getAllByRole("button", {
      name: /Відкрити книгу: Старий і море/,
    })[0]
  );
  await user.click(
    await screen.findByRole("button", { name: /Слухати розділ 1/ })
  );
  await screen.findByRole("dialog", { name: /Розділ 1/ }, { timeout: 3_000 });
}

beforeEach(() => {
  globalThis.indexedDB = new IDBFactory();
  setUiLocale("uk");
  window.localStorage.clear();
  window.localStorage.setItem(
    "slukhayka.first_language_choice.answered",
    "true"
  );
  window.history.replaceState(null, "", window.location.href);
  setViewportWidth(600);
  vi.spyOn(api, "workFeed").mockResolvedValue(page);
  vi.spyOn(api, "workSearch").mockResolvedValue(page);
  vi.spyOn(api, "catalog").mockResolvedValue({ sections: [] });
  vi.spyOn(api, "book").mockResolvedValue(detail);
  vi.spyOn(AudioEngine.prototype, "loadBookAndAwaitPlaying").mockImplementation(
    async function (
      this: AudioEngine,
      book,
      startChapter = 0,
      _timeout,
      opts = {}
    ) {
      // jsdom has no media pipeline: run the REAL load (that populates the
      // engine/mini-player) and confirm the attempt like a device would.
      await this.loadBook(book, startChapter, opts);
      return true;
    }
  );
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  window.history.replaceState(null, "", window.location.href);
  setViewportWidth(1024);
});

describe("pane close history (#1697)", () => {
  it("closing the pane returns to the book page, and the next Back is ONE step", async () => {
    const user = userEvent.setup();
    render(<App profile={profile} />);
    await openBookPageThenOverlay();

    setViewportWidth(1200, true);
    await waitFor(() =>
      expect(document.querySelector(".player-sheet-pane")).not.toBeNull()
    );
    await user.click(screen.getByRole("button", { name: /Закрити/ }));
    await waitFor(() =>
      expect(document.querySelector(".player-sheet")).toBeNull()
    );
    // The book page under the pane is still there (history did not roll back).
    expect(
      screen.getByRole("button", { name: /Слухати розділ 1/ })
    ).toBeTruthy();

    act(() => {
      window.history.back();
    });
    // One Back closes the book page — no dead player step in between.
    await waitFor(() =>
      expect(
        screen.queryByRole("button", { name: /Слухати розділ 1/ })
      ).toBeNull()
    );
    expect(isPlayerOverlayState(history.state)).toBe(false);
  });
});

describe("full-screen player modal on the real app (#1688)", () => {
  it("Escape closes it, drops the marker and returns focus to the mini-player", async () => {
    render(<App profile={profile} />);
    const dialog = await openPlayerFromCatalogue();
    expect(dialog.getAttribute("aria-modal")).toBe("true");
    expect(isPlayerOverlayState(history.state)).toBe(true);

    fireEvent.keyDown(document, { key: "Escape" });

    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    await waitFor(() =>
      expect(document.activeElement).toBe(
        screen.getByRole("button", { name: "Відкрити плеєр" })
      )
    );
    // The user close removed its own history entry: Back afterwards is not an
    // extra dead step. (A stale forward marker is handled by the next test.)
    expect(isPlayerOverlayState(history.state)).toBe(false);
  });

  it("one system Back closes the overlay and keeps the view beneath it", async () => {
    render(<App profile={profile} />);
    await openPlayerFromCatalogue();

    act(() => {
      window.history.back();
    });

    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    // The catalogue row under the overlay is still there — Back closed the
    // player, not the page.
    expect(screen.getAllByText("Старий і море").length).toBeGreaterThan(0);
  });
});

describe("crossing the pane line with the player open (#1688)", () => {
  it("overlay → pane drops the stale marker, and ✕ closes the pane normally", async () => {
    const user = userEvent.setup();
    render(<App profile={profile} />);
    await openPlayerFromCatalogue();
    expect(isPlayerOverlayState(history.state)).toBe(true);

    // Rotate / enlarge: the SAME player becomes the non-modal pane.
    setViewportWidth(1200, true);

    await waitFor(() =>
      expect(document.querySelector(".player-sheet-pane")).not.toBeNull()
    );
    // The modal marker must not outlive the modal: it is popped silently.
    await waitFor(() =>
      expect(history.state).not.toSatisfy(isPlayerOverlayState)
    );
    expect(screen.queryByRole("dialog")).toBeNull();

    await user.click(screen.getByRole("button", { name: /Закрити/ }));
    await waitFor(() =>
      expect(document.querySelector(".player-sheet")).toBeNull()
    );
  });

  it("a Forward into the spent marker never closes the book page beneath", async () => {
    render(<App profile={profile} />);

    // Open a real book page under the player, so a wrong popstate handler is
    // visible as the page disappearing.
    await openBookPageThenOverlay();
    expect(screen.queryByRole("dialog")).not.toBeNull();
    fireEvent.keyDown(document, { key: "Escape" });
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());

    // Back left the marker in the FORWARD stack; a real Forward delivers that
    // marker as the popstate state. Reacting to it as a view would close the
    // book page (the old #1688 bug) — it must be ignored.
    act(() => {
      window.dispatchEvent(
        new PopStateEvent("popstate", { state: PLAYER_OVERLAY_MARKER })
      );
    });

    await waitFor(() =>
      expect(
        screen.getByRole("button", { name: /Слухати розділ 1/ })
      ).toBeTruthy()
    );
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("keeps the pane non-modal from the start (no marker for a pane-only player)", async () => {
    setViewportWidth(1200, true);
    render(<App profile={profile} />);
    const user = userEvent.setup();
    await user.click(
      await screen.findByRole("button", { name: /Слухати: Старий і море/ })
    );
    await waitFor(() =>
      expect(document.querySelector(".player-sheet-pane")).not.toBeNull()
    );
    expect(isPlayerOverlayState(history.state)).toBe(false);
    expect(screen.queryByRole("dialog")).toBeNull();
  });
});

describe("player history witnesses (#1697)", () => {
  it("reopening the player over a spent forward marker closes with ONE ✕", async () => {
    const user = userEvent.setup();
    render(<App profile={profile} />);
    await openBookPageThenOverlay();

    fireEvent.keyDown(document, { key: "Escape" });
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());

    // A real traversal into the spent marker: the App ignores it as a view and
    // the book page stays.
    await act(async () => {
      window.history.forward();
    });
    await waitFor(() => expect(history.state).toSatisfy(isPlayerOverlayState));
    expect(
      screen.getByRole("button", { name: /Слухати розділ 1/ })
    ).toBeTruthy();

    // Reopening must NOT fork a second marker (push guard): one ✕ closes.
    await user.click(screen.getByRole("button", { name: /Слухати розділ 1/ }));
    await screen.findByRole("dialog", { name: /Розділ 1/ });
    await user.click(screen.getByRole("button", { name: /Закрити/ }));
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    expect(
      screen.getByRole("button", { name: /Слухати розділ 1/ })
    ).toBeTruthy();
  });

  it("a deferred pane pop never closes an overlay shrunk back in the same task", async () => {
    render(<App profile={profile} />);
    await openPlayerFromCatalogue();
    expect(isPlayerOverlayState(history.state)).toBe(true);

    // Cross to pane and IMMEDIATELY back before the effect's history.back()
    // is delivered: the stale marker must be consumed, not read as a real Back.
    setViewportWidth(1200, true);
    setViewportWidth(600, true);
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 150));
    });
    expect(screen.getByRole("dialog", { name: /Розділ 1/ })).toBeTruthy();
  });
});
