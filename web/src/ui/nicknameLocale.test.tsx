// @vitest-environment jsdom
/**
 * #1693 — the fallback pseudonym is a PRESENTATION of the uid, so a UI locale
 * switch re-renders it in the new language immediately (no reload). The App
 * recomputes `localizedProfile` on every locale change; this test drives the
 * real Settings screen and watches the rendered value change in place.
 */
import "fake-indexeddb/auto";
import { IDBFactory } from "fake-indexeddb";
import { act, cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "../App";
import { api } from "../api/client";
import { setUiLocale } from "../i18n/locale";
import type { ListenerProfile } from "../identity/listenerIdentity";

const profile: ListenerProfile = { uid: "uid-42", nickname: "Слухач-4379" };

beforeEach(() => {
  globalThis.indexedDB = new IDBFactory();
  setUiLocale("uk");
  window.localStorage.clear();
  window.localStorage.setItem(
    "slukhayka.first_language_choice.answered",
    "true"
  );
  vi.spyOn(api, "workFeed").mockResolvedValue({ works: [] });
  vi.spyOn(api, "workSearch").mockResolvedValue({ works: [] });
  vi.spyOn(api, "catalog").mockResolvedValue({ sections: [] });
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("nickname locale (#1693)", () => {
  it("re-renders the existing fallback pseudonym when the UI language changes", async () => {
    const user = userEvent.setup();
    render(<App profile={profile} />);

    await user.click(
      await screen.findByRole("button", { name: "Налаштування" })
    );
    // The nickname lives on the Profile sub-screen.
    await user.click(await screen.findByText(/^Профіль/));
    expect(await screen.findByText("Слухач-4379")).toBeTruthy();

    act(() => setUiLocale("en"));

    // Same uid, SAME number, English base — no reload needed.
    expect(await screen.findByText("Listener-4379")).toBeTruthy();
    expect(screen.queryByText("Слухач-4379")).toBeNull();
  });

  it("keeps a nickname the listener chose byte-identical across a language switch", async () => {
    const user = userEvent.setup();
    render(<App profile={{ uid: "uid-42", nickname: "Книголюб" }} />);

    await user.click(
      await screen.findByRole("button", { name: "Налаштування" })
    );
    await user.click(await screen.findByText(/^Профіль/));
    expect(await screen.findByText("Книголюб")).toBeTruthy();

    act(() => setUiLocale("en"));

    // A chosen name is data: the switch does not rename the listener.
    expect(await screen.findByText("Книголюб")).toBeTruthy();
    expect(screen.queryByText(/^Listener-\d{4}$/)).toBeNull();
  });
});
