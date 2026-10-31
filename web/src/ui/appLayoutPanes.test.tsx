// @vitest-environment jsdom
/**
 * #1236 — the wide-window contract, exercised on the REAL `<App/>`:
 *
 * - 599 px is a phone: one surface at a time, the bottom bar, and Back returns
 *   to the list with its place and focus.
 * - 600 px is the shared-map split: the list stays, the selected book sits
 *   beside it, and there is NO empty right-hand pane before a selection.
 * - 840 px is the player line (pinned in adaptiveLayout.test.tsx); this file
 *   keeps the navigation shell honest at the two list-detail widths.
 *
 * The book/page data comes from the worker seam, mocked the same way the
 * catalogue tests do it — the assertions are about layout and context, not
 * about parsing a source.
 */
import "fake-indexeddb/auto";
import { IDBFactory } from "fake-indexeddb";
import { act, cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "../App";
import { api } from "../api/client";
import { DomainStore } from "../local/domain";
import { setUiLocale } from "../i18n/locale";
import { readFileSync } from "node:fs";
import { MiniPlayer } from "./MiniPlayer";
import type { ListenerProfile } from "../identity/listenerIdentity";
import type { BookDetail, UnifiedWorkPage } from "../worker/types";

const profile: ListenerProfile = { uid: "local-abc123", nickname: "Слухач-1" };

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
  chapters: [
    {
      title: "Розділ 1",
      streamUrl: "https://s/old-man/1.mp3",
      durationSeconds: 3600,
    },
  ],
  otherNarrations: [],
  relatedBooks: [],
};

function setViewportWidth(widthPx: number, notify = false): void {
  Object.defineProperty(window, "innerWidth", {
    configurable: true,
    writable: true,
    value: widthPx,
  });
  if (notify)
    act(() => {
      window.dispatchEvent(new Event("resize"));
    });
}

function fullApp(): void {
  render(<App profile={profile} />);
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
  vi.spyOn(api, "workFeed").mockResolvedValue(page);
  vi.spyOn(api, "workSearch").mockResolvedValue(page);
  vi.spyOn(api, "catalog").mockResolvedValue({ sections: [] });
  vi.spyOn(api, "book").mockResolvedValue(detail);
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  setViewportWidth(1024);
  window.history.replaceState(null, "", window.location.href);
});

describe("#1236 the list-detail split", () => {
  it("at 599 px shows one surface at a time and gives Back the list scroll and focus", async () => {
    const user = userEvent.setup();
    setViewportWidth(599);
    fullApp();
    const row = (
      await screen.findAllByRole("button", {
        name: "Відкрити книгу: Старий і море",
      })
    )[0]!;
    // The compact surface keeps the phone's bottom bar.
    expect(screen.getAllByRole("tab")).toHaveLength(4);
    expect(document.querySelector(".list-detail")).toBeNull();

    const surface = document.querySelector(".surface") as HTMLElement;
    surface.scrollTop = 144;
    await user.click(row);

    // Only the page is drawn; the list is not beside it.
    await screen.findByRole("heading", { level: 1, name: "Старий і море" });
    expect(document.querySelector(".list-detail")).toBeNull();

    await user.click(screen.getByRole("button", { name: "← Назад" }));
    await screen.findAllByRole("button", {
      name: "Відкрити книгу: Старий і море",
    });
    await waitFor(() =>
      expect(document.activeElement?.getAttribute("aria-label")).toBe(
        "Відкрити книгу: Старий і море"
      )
    );
    await waitFor(() =>
      expect(
        (document.querySelector(".surface") as HTMLElement).scrollTop
      ).toBe(144)
    );
  });

  it("at 600 px keeps the list beside the selected book and never draws an empty pane", async () => {
    const user = userEvent.setup();
    setViewportWidth(600);
    fullApp();
    await screen.findAllByRole("button", {
      name: "Відкрити книгу: Старий і море",
    });

    // Before a selection the whole width belongs to the list: no pane at all.
    expect(document.querySelector(".list-detail")).toBeNull();
    expect(document.querySelector(".detail-pane")).toBeNull();

    await user.click(
      (
        await screen.findAllByRole("button", {
          name: "Відкрити книгу: Старий і море",
        })
      )[0]!
    );
    await waitFor(() =>
      expect(document.querySelector(".list-detail")).not.toBeNull()
    );
    const list = document.querySelector(".list-pane") as HTMLElement;
    const pane = document.querySelector(".detail-pane") as HTMLElement;
    expect(list.textContent).toContain("Старий і море");
    expect(pane.textContent).toContain("Старий і море");
    expect(pane.textContent).toContain("Розділ 1");
    // Opening a detail moves focus to the page; Back hands it to the row.
    await waitFor(() => expect(document.activeElement).toBe(pane));
    await user.click(screen.getByRole("button", { name: "← Назад" }));
    await waitFor(() =>
      expect(document.querySelector(".list-detail")).toBeNull()
    );
    await waitFor(() =>
      expect(document.activeElement?.getAttribute("aria-label")).toBe(
        "Відкрити книгу: Старий і море"
      )
    );
  });

  it("lets browser Back close the detail instead of leaving the document", async () => {
    const user = userEvent.setup();
    setViewportWidth(600);
    fullApp();
    await user.click(
      (
        await screen.findAllByRole("button", {
          name: "Відкрити книгу: Старий і море",
        })
      )[0]!
    );
    await waitFor(() =>
      expect(document.querySelector(".list-detail")).not.toBeNull()
    );

    act(() => {
      window.history.back();
    });
    await waitFor(() =>
      expect(document.querySelector(".list-detail")).toBeNull()
    );
    expect(screen.getAllByRole("tab")).toHaveLength(3);
    expect(document.querySelector(".detail-pane")).toBeNull();
    await waitFor(() =>
      expect(document.activeElement?.getAttribute("aria-label")).toBe(
        "Відкрити книгу: Старий і море"
      )
    );
  });

  it("keeps the split across a live resize across the 600 px line", async () => {
    const user = userEvent.setup();
    setViewportWidth(600);
    fullApp();
    await user.click(
      (
        await screen.findAllByRole("button", {
          name: "Відкрити книгу: Старий і море",
        })
      )[0]!
    );
    await waitFor(() =>
      expect(document.querySelector(".list-detail")).not.toBeNull()
    );

    setViewportWidth(599, true);
    await waitFor(() =>
      expect(document.querySelector(".list-detail")).toBeNull()
    );
    expect(document.querySelector(".detail-pane")).not.toBeNull();

    setViewportWidth(600, true);
    await waitFor(() =>
      expect(document.querySelector(".list-detail")).not.toBeNull()
    );
  });

  it("opens Settings from the header gear and returns to the same root on Back", async () => {
    const user = userEvent.setup();
    setViewportWidth(1024);
    fullApp();
    await waitFor(() =>
      expect(document.querySelector(".nav-rail")).not.toBeNull()
    );
    const gear = screen.getByRole("button", { name: "Налаштування" });
    await user.click(gear);
    await screen.findByRole("heading", { level: 1, name: "Налаштування" });
    // Settings is a route, not a rail item.
    expect(screen.getAllByRole("tab")).toHaveLength(3);

    await user.click(screen.getByRole("button", { name: "← Назад" }));
    await screen.findByRole("heading", { level: 1, name: "Огляд" });
    expect(window.localStorage.getItem("slukhayka.selected_tab")).toBe(
      "explore"
    );
    await waitFor(() =>
      expect(document.activeElement?.getAttribute("aria-label")).toBe(
        "Налаштування"
      )
    );
  });

  it("returns focus to the gear in English too", async () => {
    // #1563 — the gear used to be found by the Ukrainian literal
    // «Налаштування», so the return contract silently broke in English.
    setUiLocale("en");
    const user = userEvent.setup();
    setViewportWidth(1024);
    fullApp();
    await waitFor(() =>
      expect(document.querySelector(".nav-rail")).not.toBeNull()
    );
    const gear = screen.getByRole("button", { name: "Settings" });
    await user.click(gear);
    await screen.findByRole("heading", { level: 1, name: "Settings" });

    await user.click(screen.getByRole("button", { name: "← Back" }));
    await waitFor(() =>
      expect(document.activeElement?.getAttribute("aria-label")).toBe(
        "Settings"
      )
    );
  });

  it("gives a Library row the same contract, with the local facts it really owns", async () => {
    const user = userEvent.setup();
    const domain = new DomainStore();
    await domain.addLibraryEntry({ title: "Кобзар", author: "Тарас Шевченко" });
    window.localStorage.setItem("slukhayka.selected_tab", "library");
    setViewportWidth(600);
    fullApp();

    const row = await screen.findByRole("button", {
      name: "Відкрити книгу: Кобзар",
    });
    expect(document.querySelector(".detail-pane")).toBeNull();
    await user.click(row);
    await waitFor(() =>
      expect(document.querySelector(".list-detail")).not.toBeNull()
    );
    expect(document.querySelector(".detail-pane")?.textContent).toContain(
      "Кобзар"
    );
    // No fabricated Source URL: the pane says what the Library row really owns.
    expect(document.querySelector(".detail-pane")?.textContent).toContain(
      "Джерело цієї книги не збережене на вебі"
    );
  });
});

describe("mini-player a11y (#1689)", () => {
  /** A tiny engine seam: the strip only reads state and commands transport. */
  function fakeEngine(status: "playing" | "paused") {
    return {
      getState: () => ({
        status,
        chapterIndex: 0,
        positionSeconds: 42,
        speed: 1,
        isCompleted: false,
      }),
      subscribe: () => () => undefined,
      skip: vi.fn(),
      play: vi.fn(),
      pause: vi.fn(),
    } as unknown as import("../player/audioEngine").AudioEngine;
  }

  it("opens the full player from the keyboard with Enter and Space", async () => {
    const user = userEvent.setup();
    const onExpand = vi.fn();
    render(<MiniPlayer engine={fakeEngine("paused")} onExpand={onExpand} />);

    const open = screen.getByRole("button", { name: "Відкрити плеєр" });
    open.focus();
    await user.keyboard("{Enter}");
    expect(onExpand).toHaveBeenCalledTimes(1);
    await user.keyboard(" ");
    expect(onExpand).toHaveBeenCalledTimes(2);
  });

  it("gives play/pause and +15s localized names", async () => {
    const user = userEvent.setup();
    const paused = fakeEngine("paused");
    const { unmount } = render(
      <MiniPlayer engine={paused} onExpand={vi.fn()} />
    );
    await user.click(screen.getByRole("button", { name: "Грати" }));
    expect(paused.play).toHaveBeenCalledTimes(1);
    unmount();

    const playing = fakeEngine("playing");
    render(<MiniPlayer engine={playing} onExpand={vi.fn()} />);
    await user.click(screen.getByRole("button", { name: "Пауза" }));
    expect(playing.pause).toHaveBeenCalledTimes(1);
    expect(
      screen.getByRole("button", {
        name: "Перемотати вперед на 15 секунд",
      })
    ).toBeTruthy();
  });

  it("keeps the 48 px floor through one class, not per-button inline magic", () => {
    render(<MiniPlayer engine={fakeEngine("paused")} onExpand={vi.fn()} />);
    const transport = Array.from(
      document.querySelectorAll<HTMLButtonElement>(
        ".mini-player .mini-transport"
      )
    );
    expect(transport).toHaveLength(2);
    expect(
      document.querySelector<HTMLButtonElement>(
        ".mini-player .mini-player-expand"
      )
    ).toBeTruthy();
    // The floor itself is a stylesheet rule (jsdom has no layout engine).
    // CI runs `cd web` (documented in package scripts and the workflow), so the
    // stylesheet is read from the module's documented working directory.
    const themeCss = readFileSync("src/theme.css", "utf8");
    const rule = themeCss.match(
      /\.mini-player \.mini-transport,\s*\.mini-player \.mini-player-expand \{([\s\S]*?)\}/
    );
    expect(rule).not.toBeNull();
    // BOTH dimensions and BOTH selectors: dropping only min-width must be red.
    expect(rule![1]).toMatch(/min-width:\s*48px/);
    expect(rule![1]).toMatch(/min-height:\s*48px/);
    expect(rule![1]).toMatch(/font:\s*inherit/);
    // The expand row keeps its text at the start; only the transport glyphs are
    // centred (a shared `justify-content: center` drifted the chapter text).
    expect(themeCss).toMatch(
      /\.mini-player \.mini-player-expand \{[^}]*justify-content:\s*flex-start/
    );
    expect(themeCss).toMatch(
      /\.mini-player \.mini-transport \{[^}]*justify-content:\s*center/
    );
  });
});
