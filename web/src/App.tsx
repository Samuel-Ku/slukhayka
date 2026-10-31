import { useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import type { ListenerProfile } from "./identity/listenerIdentity";
import { Catalog } from "./ui/Catalog";
import { Listen } from "./ui/Listen";
import { Library } from "./ui/Library";
import { Settings } from "./ui/Settings";
import { BookPage } from "./ui/BookPage";
import { AudioEngine, type PlayIntent } from "./player/audioEngine";
import { MiniPlayer } from "./ui/MiniPlayer";
import { PlayerSheet } from "./ui/PlayerSheet";
import {
  PLAYER_OVERLAY_MARKER,
  isPlayerOverlayState,
} from "./ui/playerOverlayHistory";
import type {
  BookDetail,
  SourceId,
  UnifiedEdition,
  UnifiedWork,
} from "./worker/types";
import { LocalListeningStateStore, BrowserStorage } from "./player/localState";
import { HybridListeningStateStorage } from "./local/hybridListeningState";
import { IdbListeningStateStore } from "./local/listeningState";
import { DomainStore } from "./local/domain";
import { EditionLinkStore } from "./local/editionLinks";
import { ListenPrefsStore } from "./local/listenPrefs";
import { RecommendationPrefsStore } from "./local/recommendationPrefs";
import { RecommendationParticipation } from "./recommend/participation";
import { PlayerBookmarksStore } from "./player/bookmarks";
import { BrowserProgressSyncLedger } from "./sync/ledger";
import { ProgressSyncSettings } from "./sync/settings";
import { FirestoreProgressSyncStore } from "./sync/store";
import { ProgressSyncController } from "./sync/controller";
import {
  FirestoreNarrationRatingsStore,
  FirestoreReviewsStore,
} from "./reviews/store";
// spec-51 (#697, T9) — the shared listener-collections reader; null without a
// Firebase config, so every collection surface is honestly absent.
import { FirestoreCollectionsStore } from "./collections/store";
import { WorkRelationshipController } from "./sync/workRelationshipController";
import { FirestoreWorkRelationshipStore } from "./sync/workRelationshipStore";
import {
  PersonBookmarkSyncController,
  LocalPendingPersonBookmarkDeletes,
} from "./sync/personBookmarkController";
import { FirestorePersonBookmarkStore } from "./sync/personBookmarkStore";
import { getFirestoreForEnv } from "./firebase/firestore";
import { editionIdFor, mergeKeyFor } from "./sync/edition";
import { setUiLocale, useTranslate, useUiLocale } from "./i18n/locale";
import { translate } from "./i18n/strings";
import type { StringKey } from "./i18n/strings";
import {
  loadSelectedTab,
  saveSelectedTab,
  SELECTED_TAB_ORDER,
  type SelectedTab,
} from "./ui/selectedTab";
import {
  showsLeadingMiniPlayer,
  showsPlayerPane,
  useAppLayout,
  useWindowWidth,
} from "./ui/adaptiveLayout";
import {
  LibraryBookDetail,
  type LibrarySelection,
} from "./ui/LibraryBookDetail";

/**
 * #583 W1.1 (R-W7) — the bottom bar is Android's `SelectedTab` verbatim:
 * Слухати / Огляд / Медіатека / Налаштування, in the enum's order. The
 * chosen tab persists (ui/selectedTab); «Профіль» is not a tab — the
 * recovery code and the sync switch live in the Налаштування direction
 * (ui/Settings). The interim landing tab is Огляд.
 */

/** One label per tab, in the same i18n keys the Android resources mirror. */
const TAB_LABELS: Record<SelectedTab, StringKey> = {
  listen: "tabListen",
  explore: "tabCatalog",
  library: "tabLibrary",
  settings: "tabSettings",
};

/** The rail's one visual mark per root; the label carries the meaning. */
const TAB_ICONS: Record<SelectedTab, string> = {
  listen: "🎧",
  explore: "🧭",
  library: "📚",
  settings: "⚙",
};

/**
 * #1236 — the wide-window rail leads with the working ROOT sections; Settings
 * is a gear destination, not a fifth root (ADR-0049). The web has three real
 * working roots today (Слухати / Огляд / Медіатека); the shared map's fourth,
 * «Друзі», belongs to the social layer that web does not ship, and the ticket
 * explicitly keeps new root sections out of this slice. The compact bar below
 * is the legacy web structure (its fourth item is Settings) and is left
 * untouched; the rail is the shared map as far as this client can honestly
 * draw it.
 */
const ROOT_TABS: readonly SelectedTab[] = ["listen", "explore", "library"];

/** #1236 — one selection a detail pane can show: a Source book or a Library row. */
type BookSelection =
  { kind: "source"; url: string; source: SourceId } | LibrarySelection;

/** The state one browser-history entry carries; only our own shapes are read. */
interface AppHistoryState {
  slukhayka: "web-1236";
  tab: SelectedTab;
  book: BookSelection | null;
  settingsReturnTab: SelectedTab;
}

function appHistoryState(
  tab: SelectedTab,
  book: BookSelection | null,
  settingsReturnTab: SelectedTab
): AppHistoryState {
  return { slukhayka: "web-1236", tab, book, settingsReturnTab };
}

function isSelectedTab(value: unknown): value is SelectedTab {
  return (
    typeof value === "string" &&
    (SELECTED_TAB_ORDER as readonly string[]).includes(value)
  );
}

function isBookSelection(value: unknown): value is BookSelection {
  if (typeof value !== "object" || value === null) return false;
  const candidate = value as Record<string, unknown>;
  if (candidate.kind === "source") {
    return (
      typeof candidate.url === "string" && typeof candidate.source === "string"
    );
  }
  if (candidate.kind === "library") {
    return (
      typeof candidate.mergeKey === "string" &&
      typeof candidate.title === "string" &&
      typeof candidate.author === "string"
    );
  }
  return false;
}

function isAppHistoryState(value: unknown): value is AppHistoryState {
  if (typeof value !== "object" || value === null) return false;
  const candidate = value as Record<string, unknown>;
  return (
    candidate.slukhayka === "web-1236" &&
    isSelectedTab(candidate.tab) &&
    isSelectedTab(candidate.settingsReturnTab) &&
    (candidate.book === null || isBookSelection(candidate.book))
  );
}

export function App({
  profile: initialProfile,
}: {
  profile: ListenerProfile | null;
}) {
  const t = useTranslate();
  const locale = useUiLocale();
  const [profile, setProfile] = useState(initialProfile);
  useEffect(() => setProfile(initialProfile), [initialProfile]);

  const [tab, setTab] = useState<SelectedTab>(() => loadSelectedTab());
  const [settingsReturnTab, setSettingsReturnTab] =
    useState<SelectedTab>("explore");
  const [book, setBook] = useState<BookSelection | null>(null);
  const bookKey =
    book === null
      ? ""
      : book.kind === "source"
        ? `${book.source}|${book.url}`
        : book.mergeKey;
  const audioRef = useRef<HTMLAudioElement | null>(null);
  const engineRef = useRef<AudioEngine | null>(null);
  const [playerOpen, setPlayerOpen] = useState(false);
  // #1688 — closing the full-screen player must return focus to the mini-player
  // (it is unmounted while the overlay is open, so the overlay cannot restore
  // focus to a detached node on its own).
  const playerReturnFocusRef = useRef(false);
  const playerOpenRef = useRef(playerOpen);
  playerOpenRef.current = playerOpen;
  const playerPaneRef = useRef(false);
  // #1688 — a marker popped on the overlay→pane transition is NOT a close.
  const playerMarkerStaleRef = useRef(false);
  const [, forceUpdate] = useState(0);
  // #1236 — each of these is read by an event listener that must see the
  // CURRENT value: a popstate handler registered once at mount, and the
  // focus-return contract after a detail closes.
  const tabRef = useRef(tab);
  const bookRef = useRef<BookSelection | null>(book);
  const settingsReturnRef = useRef(settingsReturnTab);
  tabRef.current = tab;
  bookRef.current = book;
  settingsReturnRef.current = settingsReturnTab;
  const originFocusRef = useRef<HTMLElement | null>(null);
  const originFocusLabelRef = useRef<string | null>(null);
  const restoreFocusPendingRef = useRef(false);
  const settingsFocusRef = useRef<HTMLElement | null>(null);
  const settingsFocusPendingRef = useRef(false);
  const surfaceRef = useRef<HTMLElement | null>(null);
  const listPaneRef = useRef<HTMLDivElement | null>(null);
  const detailPaneRef = useRef<HTMLDivElement | null>(null);
  /** Per-root list scroll, so a round trip through the detail never loses place. */
  const scrollMemory = useRef<Record<string, number>>({});

  // Shared local store for engine + sync mirror: the synchronous StorageLike
  // the engine already consumes, now backed by IndexedDB (R-W8) with a boot
  // gate + buffered pre-boot writes (StrictMode double-effect safe).
  const idbStore = useMemo(() => new IdbListeningStateStore(), []);
  const hybrid = useMemo(
    () =>
      new HybridListeningStateStorage(
        idbStore,
        new BrowserStorage(window.localStorage)
      ),
    [idbStore]
  );
  // #584 W1.2 — the mergeKey → Edition join the Медіатека reads.
  const linkStore = useMemo(() => new EditionLinkStore(), []);
  // #585 W2.1 — the Слухати shelves' local-only order/hide prefs.
  const listenPrefsStore = useMemo(() => new ListenPrefsStore(), []);
  // #586 W2.2 — «Не цікаво»: the local Recommendation Preference store
  // (HIDE_WORK dictionary, local-only, reversible from Рекомендації).
  const recommendationPrefsStore = useMemo(
    () => new RecommendationPrefsStore(),
    []
  );
  // #592 W6.1 — the participation consent (Settings → Рекомендації):
  // local-only, off by default; the server layer joins later (ADR-0030).
  const participation = useMemo(
    () => new RecommendationParticipation(window.localStorage),
    []
  );
  // #590 W5.1 — the player's bookmarks (Android's BookmarkEntity, local
  // only — never synced), shared by the engine's auto-bookmark and the
  // player sheet's list.
  const bookmarksStore = useMemo(() => new PlayerBookmarksStore(), []);
  const localStore = useMemo(() => new LocalListeningStateStore(hybrid), []);
  const [boot, setBoot] = useState<{
    snapshots: number;
    evicted: boolean;
  } | null>(null);
  useEffect(() => {
    let cancelled = false;
    void hybrid.whenBooted().then((outcome) => {
      if (!cancelled)
        setBoot({
          snapshots: outcome.snapshots.length,
          evicted: outcome.evicted,
        });
    });
    return () => {
      cancelled = true;
    };
  }, [hybrid]);
  const ledger = useMemo(
    () => new BrowserProgressSyncLedger(window.localStorage),
    []
  );
  const settings = useMemo(
    () => new ProgressSyncSettings(window.localStorage),
    []
  );
  const firestore = useMemo(() => getFirestoreForEnv(import.meta.env), []);
  const syncStore = useMemo(
    () => (firestore ? new FirestoreProgressSyncStore(firestore) : null),
    [firestore]
  );
  // W4.1 — the reviews block exists only when Firebase is configured (Android's
  // `listenerReviews != null` gate): no config → no «Відгуки» block at all.
  const reviewsStore = useMemo(
    () => (firestore ? new FirestoreReviewsStore(firestore) : null),
    [firestore]
  );
  const narrationRatingsStore = useMemo(
    () => (firestore ? new FirestoreNarrationRatingsStore(firestore) : null),
    [firestore]
  );
  // spec-51 (#697) — same gate as the reviews block: no Firebase config → no
  // block, no rail, no collection screen (never a fake empty community).
  const collectionsStore = useMemo(
    () => (firestore ? new FirestoreCollectionsStore(firestore) : null),
    [firestore]
  );

  // Identity reads current profile (updated after restore) — no writes before binding.
  const profileRef = useRef(profile);
  useEffect(() => {
    profileRef.current = profile;
  }, [profile]);

  // #582 W0.4 — person-bookmark sync rides the same delivery as the Work
  // relationships (the R-W10 seam): deterministic ids, local-first toggle,
  // pull/merge at the same moments, refusing sync never deletes local rows.
  const pendingPersonDeletes = useMemo(
    () => new LocalPendingPersonBookmarkDeletes(window.localStorage),
    []
  );
  const personBookmarkStore = useMemo(
    () => (firestore ? new FirestorePersonBookmarkStore(firestore) : null),
    [firestore]
  );

  // #581 W0.3 — the Work-relationship sync (entry/tombstone mirror, LWW):
  // pull on boot for a returning bound session, merge at linking, push at
  // the honest moments via the callbacks below.
  const domainStore = useMemo(() => new DomainStore(), []);
  const relationshipStore = useMemo(
    () => (firestore ? new FirestoreWorkRelationshipStore(firestore) : null),
    [firestore]
  );
  const relationships = useMemo(
    () =>
      new WorkRelationshipController(
        () => profileRef.current?.uid ?? null,
        domainStore,
        relationshipStore,
        () => settings.isEnabled()
      ),
    [domainStore, relationshipStore, settings]
  );
  const personBookmarks = useMemo(
    () =>
      new PersonBookmarkSyncController(
        () => profileRef.current?.uid ?? null,
        domainStore,
        personBookmarkStore,
        () => settings.isEnabled(),
        pendingPersonDeletes
      ),
    [domainStore, personBookmarkStore, settings, pendingPersonDeletes]
  );
  useEffect(() => {
    void relationships.pullAndApply().catch(() => []);
  }, [relationships]);
  useEffect(() => {
    // #582 W0.4 — the same boot pull as the relationships: a returning bound
    // session finds the phone's bookmarks; a null store is a no-op.
    void personBookmarks.sync().catch(() => undefined);
  }, [personBookmarks]);
  const syncController = useMemo(() => {
    const mirror = {
      editionIdForSync: (bookId: string) => bookId,
      progressByEdition: (editionId: string) => {
        const snap = localStore.load(editionId);
        if (!snap) return null;
        return {
          editionId,
          chapterIndex: snap.chapterIndex,
          positionSeconds: snap.positionSeconds,
          isCompleted: snap.isCompleted,
          preferredSpeed: snap.preferredSpeed,
          updatedAtServerMs: 0,
        };
      },
      applyRemoteProgress: (
        _bookId: string,
        remote: import("./sync/policy").RemoteListeningState
      ) => {
        localStore.save({
          editionId: remote.editionId,
          chapterIndex: remote.chapterIndex,
          positionSeconds: remote.positionSeconds,
          isCompleted: remote.isCompleted,
          preferredSpeed: remote.preferredSpeed,
          lastPausedAtEpochMs: null,
        });
      },
    };
    return new ProgressSyncController(
      { getUid: () => profileRef.current?.uid ?? null },
      mirror,
      syncStore,
      ledger,
      () => settings.isEnabled()
    );
  }, [localStore, ledger, settings, syncStore]);

  if (!engineRef.current) {
    engineRef.current = new AudioEngine({
      relayBase: "/api",
      store: localStore,
      bookmarks: bookmarksStore,
    });
  }
  const engine = engineRef.current;
  // Keep engine's sync controller in sync with current profile/settings.
  // Set synchronously so the first loadBook after restore sees the bound controller.
  engine.setSyncController(syncController);

  useEffect(() => {
    document.title = translate(locale, "docTitle");
    const unsub = engine.subscribe(() => forceUpdate((n) => n + 1));
    return unsub;
  }, [engine, locale]);

  useEffect(() => {
    if (audioRef.current) engine.attachAudio(audioRef.current);
  }, [engine]);

  // #1236 — the two width answers the shared map asks for. They are read here,
  // above every hook that depends on them, so the pane and the leading column
  // flip on a live `resize` without a reload.
  const layout = useAppLayout();
  const windowWidth = useWindowWidth();
  const playerPane = showsPlayerPane(windowWidth, playerOpen);
  playerPaneRef.current = playerPane;
  const split = layout === "expanded" && (book !== null || playerPane);
  const leadingMiniPlayer =
    layout === "expanded" &&
    !playerPane &&
    showsLeadingMiniPlayer(windowWidth, engine.getState().status !== "idle");

  const replaceHistory = (
    nextTab: SelectedTab,
    nextBook: BookSelection | null,
    returnTab: SelectedTab
  ): void => {
    window.history.replaceState(
      appHistoryState(nextTab, nextBook, returnTab),
      "",
      window.location.href
    );
  };
  const pushHistory = (
    nextTab: SelectedTab,
    nextBook: BookSelection | null,
    returnTab: SelectedTab
  ): void => {
    window.history.pushState(
      appHistoryState(nextTab, nextBook, returnTab),
      "",
      window.location.href
    );
  };

  /** The row/card that opened the detail gets focus back when it closes. */
  const restoreOriginFocus = (): void => {
    restoreFocusPendingRef.current = true;
  };

  const openBook = (selection: BookSelection): void => {
    const active = document.activeElement;
    originFocusRef.current = active instanceof HTMLElement ? active : null;
    originFocusLabelRef.current =
      active instanceof HTMLElement ? active.getAttribute("aria-label") : null;
    // Capture the list's place at the moment of selection: the compact surface
    // is about to be replaced, and a synthetic scroll (a test, a restored
    // page) need not have produced an `onScroll` event yet.
    const listNode = split ? listPaneRef.current : surfaceRef.current;
    if (listNode !== null && (book === null || split)) {
      scrollMemory.current[tabRef.current] = listNode.scrollTop;
    }
    setBook(selection);
    pushHistory(tabRef.current, selection, settingsReturnRef.current);
  };

  const closeBook = (): void => {
    // A Back button is a browser Back: the history entry we pushed is the one
    // that must be popped, so a later browser Back cannot replay the detail.
    if (
      isAppHistoryState(window.history.state) &&
      window.history.state.book !== null
    ) {
      window.history.back();
      return;
    }
    restoreOriginFocus();
    setBook(null);
    replaceHistory(tabRef.current, null, settingsReturnRef.current);
  };

  const openSettings = (): void => {
    const active = document.activeElement;
    settingsFocusRef.current = active instanceof HTMLElement ? active : null;
    const returnTab = ROOT_TABS.includes(tabRef.current)
      ? tabRef.current
      : settingsReturnRef.current;
    setSettingsReturnTab(returnTab);
    setTab("settings");
    saveSelectedTab("settings");
    setBook(null);
    pushHistory("settings", null, returnTab);
  };

  const leaveSettings = (): void => {
    if (
      isAppHistoryState(window.history.state) &&
      window.history.state.tab === "settings"
    ) {
      window.history.back();
      return;
    }
    const next = settingsReturnRef.current;
    settingsFocusPendingRef.current = true;
    setTab(next);
    saveSelectedTab(next);
    replaceHistory(next, null, next);
  };

  const selectRoot = (next: SelectedTab): void => {
    if (next === "settings") {
      openSettings();
      return;
    }
    setTab(next);
    saveSelectedTab(next);
    setBook(null);
    replaceHistory(next, null, settingsReturnRef.current);
  };

  // #1236 — browser Back is part of the contract, not a page reload. Our own
  // entries carry the whole view (root, open detail, settings return root), so
  // Back restores the previous one instead of dropping the listener on a stale
  // screen. A foreign entry is never interpreted as our data.
  useEffect(() => {
    const onPopState = (event: PopStateEvent): void => {
      const state = event.state;
      // #1688 — the overlay→pane transition popped our marker itself; that
      // traversal is not a navigation and must not touch the view.
      if (playerMarkerStaleRef.current) {
        playerMarkerStaleRef.current = false;
        return;
      }
      // A stale FORWARD entry carrying our player marker must never be read as
      // a view (it used to close the open book underneath). Ignore it.
      if (isPlayerOverlayState(state)) return;
      // The overlay is open and a REAL Back happened: close the player and
      // keep the view beneath untouched.
      if (playerOpenRef.current && !playerPaneRef.current) {
        playerReturnFocusRef.current = true;
        setPlayerOpen(false);
        return;
      }
      if (!isAppHistoryState(state)) {
        setBook(null);
        return;
      }
      const hadBook = bookRef.current !== null;
      const hadSettings = tabRef.current === "settings";
      setTab(state.tab);
      saveSelectedTab(state.tab);
      setSettingsReturnTab(state.settingsReturnTab);
      setBook(state.book);
      if (hadBook && state.book === null) restoreOriginFocus();
      if (hadSettings && state.tab !== "settings")
        settingsFocusPendingRef.current = true;
    };
    window.addEventListener("popstate", onPopState);
    return () => window.removeEventListener("popstate", onPopState);
  }, []);

  // The first entry belongs to this app's shape; without it a Back from the
  // first detail would leave the document instead of closing the detail.
  useEffect(() => {
    replaceHistory(tabRef.current, bookRef.current, settingsReturnRef.current);
    // Mount only: later transitions write their own entries.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // A newly opened detail owns focus (keyboard users land on the page, not in
  // the list they just left); closing hands it back to the originating row.
  // The return runs AFTER the commit, so the compact path's remounted list is
  // in the document when the stable aria-label is looked up.
  useEffect(() => {
    if (bookKey !== "") {
      const pane = detailPaneRef.current;
      if (pane !== null) {
        pane.focus();
        pane.scrollTop = 0;
      }
      return;
    }
    if (!restoreFocusPendingRef.current) return;
    restoreFocusPendingRef.current = false;
    const origin = originFocusRef.current;
    if (origin?.isConnected === true) {
      origin.focus();
      return;
    }
    // The compact path unmounts the list and its data loads asynchronously,
    // so the exact node is gone and the remounted row appears a tick later.
    // Its accessible name is the stable handle; observe until it is drawn,
    // and give up after a bounded wait rather than polling forever.
    const label = originFocusLabelRef.current;
    if (label === null) return;
    const focusReplacement = (): boolean => {
      const replacement = Array.from(
        document.querySelectorAll<HTMLElement>("[aria-label]")
      ).find((node) => node.getAttribute("aria-label") === label);
      if (replacement === undefined) return false;
      replacement.focus();
      return true;
    };
    if (focusReplacement() || typeof MutationObserver === "undefined") return;
    const observer = new MutationObserver(() => {
      if (focusReplacement()) observer.disconnect();
    });
    observer.observe(document.body, { childList: true, subtree: true });
    window.setTimeout(() => observer.disconnect(), 2000);
  }, [bookKey]);

  // The gear is the Settings entry on every root, so leaving Settings hands
  // focus back to it — the same return contract the detail rows have.
  useEffect(() => {
    if (tab === "settings" || !settingsFocusPendingRef.current) return;
    settingsFocusPendingRef.current = false;
    const origin = settingsFocusRef.current;
    if (origin?.isConnected === true) {
      origin.focus();
      return;
    }
    // #1563 — the gear is located by a STABLE marker, not by its localized
    // aria-label: in English the old literal «Налаштування» matched nothing,
    // so leaving Settings dropped keyboard/TalkBack focus into the void.
    const fallback = document.querySelector<HTMLElement>(
      "[data-settings-entry]"
    );
    fallback?.focus();
  }, [tab]);

  // The list's scroll place survives a detail round trip AND a resize across
  // the 600 px line: the compact path unmounts the list, the split path keeps
  // it mounted, and both read the same per-root memory.
  useEffect(() => {
    if (book !== null && !split) return;
    const node = split ? listPaneRef.current : surfaceRef.current;
    if (node === null) return;
    const top = scrollMemory.current[tab] ?? 0;
    if (top === 0) {
      node.scrollTop = 0;
      return;
    }
    let cancelled = false;
    let frame = 0;
    let attempts = 0;
    const restore = (): void => {
      if (cancelled || !node.isConnected) return;
      node.scrollTop = top;
      // The remounted screen fetches its list asynchronously; until the
      // content is tall enough the browser clamps the assignment. Retry for a
      // bounded number of frames instead of leaving the listener at position
      // zero on a slow IndexedDB read.
      if (Math.abs(node.scrollTop - top) > 1 && attempts < 180) {
        attempts += 1;
        frame =
          typeof requestAnimationFrame === "function"
            ? requestAnimationFrame(restore)
            : 0;
      }
    };
    restore();
    return () => {
      cancelled = true;
      if (frame !== 0) cancelAnimationFrame(frame);
    };
  }, [tab, bookKey, split, layout]);

  // W4.1 — «Запит після завершення книги відкриває ту саму форму»: the
  // player remembers the last played Work so the finish prompt can open the
  // SAME review form the book page uses (the AC's single entry — no second
  // buttons anywhere).
  const lastPlayedRef = useRef<{
    title: string;
    author: string;
    narrator?: string;
    language?: string;
    url: string;
  } | null>(null);
  const handlePlay = async (
    detail: BookDetail,
    chapterIndex: number,
    intent?: PlayIntent
  ): Promise<boolean> => {
    lastPlayedRef.current = {
      title: detail.title,
      author: detail.author,
      narrator: detail.narrator,
      language: detail.language,
      url: detail.url,
    };
    const mergeKey = mergeKeyFor(detail.title, detail.author);
    const editionId = editionIdFor(mergeKey, detail.url, detail.narrator ?? "");
    // #584 W1.2 — write the edition link at the moment the app knows both
    // sides: the Медіатека's hairline and Нові/Слухаю/Завершені join on it.
    // Never guessed: durations count only when every chapter declared one.
    const chapterDurations = detail.chapters.map(
      (chapter) => chapter.durationSeconds ?? Number.NaN
    );
    const durationsKnown =
      detail.chapters.length > 0 &&
      chapterDurations.every(
        (seconds) => Number.isFinite(seconds) && seconds > 0
      );
    void linkStore.link({
      editionId,
      mergeKey,
      narrator: detail.narrator ?? "",
      language: detail.language ?? "",
      durationSeconds:
        detail.totalDurationSeconds ??
        (durationsKnown
          ? chapterDurations.reduce((sum, seconds) => sum + seconds, 0)
          : null),
      chapterDurations: durationsKnown ? chapterDurations : null,
    });
    const playing = await engine.loadBookAndAwaitPlaying(
      {
        title: detail.title,
        chapters: detail.chapters,
        editionId,
        workId: mergeKey,
      },
      chapterIndex,
      undefined,
      // #611 — a Chapter row is an explicit pick that starts there from zero;
      // a card's Play (no intent) resumes this Edition's Listening State.
      { forceChapter: intent?.explicitChapter === true }
    );
    if (playing) setPlayerOpen(true);
    return playing;
  };

  /** #584 W1.2 — «зберегти» on an Огляд card creates the Library Entry. */
  const handleSaveWork = (work: UnifiedWork, edition: UnifiedEdition): void => {
    void domainStore
      .addLibraryEntry({ title: work.title, author: work.author })
      .then(() =>
        linkStore.link({
          editionId: edition.id,
          mergeKey: work.mergeKey,
          narrator: edition.narrator ?? "",
          language: edition.language ?? "",
          durationSeconds: edition.durationSeconds ?? null,
          chapterDurations: null,
        })
      )
      .then(() => relationships.pushAfterChange(work.mergeKey))
      .catch(() => undefined);
  };
  const renderRoot = (): ReactNode => {
    if (boot === null) {
      // The hydration gate: no screen reads listener data before IDB boot
      // (migration + hydration) has settled — R-W8's loss-free bar.
      return <div className="placeholder">{t("storageLoading")}</div>;
    }
    if (tab === "listen") {
      return (
        <Listen
          domainStore={domainStore}
          linkStore={linkStore}
          listening={idbStore}
          prefsStore={listenPrefsStore}
          recommendationPrefs={recommendationPrefsStore}
          collectionsStore={collectionsStore}
          profile={profile}
        />
      );
    }
    if (tab === "explore") {
      return (
        <Catalog
          onOpenBook={(url, source) =>
            openBook({ kind: "source", url, source })
          }
          onPlay={handlePlay}
          onSaveWork={handleSaveWork}
          domainStore={domainStore}
          linkStore={linkStore}
          listening={idbStore}
          recommendationPrefs={recommendationPrefsStore}
          participation={participation}
          personBookmarks={personBookmarks}
          showFirstLanguageChoice
        />
      );
    }
    return (
      <Library
        domainStore={domainStore}
        linkStore={linkStore}
        listening={idbStore}
        pushAfterChange={(mergeKey) => relationships.pushAfterChange(mergeKey)}
        personBookmarks={personBookmarks}
        onOpen={(view) =>
          openBook({
            kind: "library",
            mergeKey: view.mergeKey,
            title: view.title,
            author: view.author,
            narrator: view.narrator,
            status: view.status,
            progress: view.progress,
          })
        }
        selectedMergeKey={book?.kind === "library" ? book.mergeKey : null}
      />
    );
  };

  const renderDetail = (): ReactNode => {
    if (book === null) return null;
    if (book.kind === "library") return <LibraryBookDetail book={book} />;
    return (
      <BookPage
        url={book.url}
        source={book.source}
        onOpenBook={(url, source) => openBook({ kind: "source", url, source })}
        onPlay={handlePlay}
        profile={profile}
        reviewsStore={reviewsStore}
        narrationRatingsStore={narrationRatingsStore}
        collectionsStore={collectionsStore}
        domainStore={domainStore}
        personBookmarks={personBookmarks}
      />
    );
  };

  // #1688 — a user close (button/Escape/Back) hands focus back to the row that
  // opened the player. The mini-player remounts on this same state change, so
  // the restore runs in an effect AFTER that commit, not in the overlay cleanup.
  const closePlayer = (): void => {
    playerReturnFocusRef.current = true;
    if (!playerPaneRef.current && isPlayerOverlayState(history.state)) {
      // Our entry is on top: popping it is what closes the overlay (and keeps
      // the browser history free of a dead step after the user closes it).
      history.back();
      return;
    }
    setPlayerOpen(false);
  };

  useEffect(() => {
    if (playerOpen || !playerReturnFocusRef.current) return;
    playerReturnFocusRef.current = false;
    document
      .querySelector<HTMLElement>(".mini-player .mini-player-expand")
      ?.focus();
  }, [playerOpen]);

  // #1688 — the overlay OWNS one history entry so ONE system Back closes the
  // player instead of leaving the app. The entry is App-owned on purpose:
  // StrictMode double-invokes effects in dev, and a component-owned history.back
  // in cleanup forked a stale marker that closed the player by itself. Pushing
  // only when the marker is absent keeps the effect idempotent, and no cleanup
  // ever calls back(); user-close goes through closePlayer below.
  useEffect(() => {
    if (!playerOpen || playerPane) return;
    if (!isPlayerOverlayState(history.state)) {
      history.pushState(PLAYER_OVERLAY_MARKER, "");
    }
  }, [playerOpen, playerPane]);

  // #1688 — when the window grows past the pane line, the SAME player stops
  // being a modal. The overlay marker must not outlive it: pop it silently so
  // ✕ closes the pane normally and no dead history step is left behind.
  useEffect(() => {
    if (!playerPane) return;
    if (!isPlayerOverlayState(history.state)) return;
    playerMarkerStaleRef.current = true;
    history.back();
  }, [playerPane]);

  const playerSheet = playerOpen ? (
    <PlayerSheet
      engine={engine}
      onClose={closePlayer}
      lastPlayed={lastPlayedRef.current}
      profile={profile}
      reviewsStore={reviewsStore}
      bookmarksStore={bookmarksStore}
      placement={playerPane ? "pane" : "overlay"}
    />
  ) : null;

  const settingsScreen = (
    <Settings
      profile={profile}
      onProfileChange={setProfile}
      evicted={boot?.evicted ?? false}
      onLinked={(uid) => {
        relationships.setUid(uid);
        void relationships.mergeAtLinking().catch(() => undefined);
        personBookmarks.setUid(uid);
        void personBookmarks.mergeAtLinking().catch(() => undefined);
      }}
      recommendationPrefs={recommendationPrefsStore}
      domainStore={domainStore}
      participation={participation}
      hybrid={hybrid}
      idbStore={idbStore}
      storage={window.localStorage}
    />
  );

  const mainContent =
    tab === "settings" ? (
      settingsScreen
    ) : split ? (
      <div className="list-detail">
        <div
          className="list-pane"
          ref={listPaneRef}
          onScroll={(event) => {
            if (book === null || split)
              scrollMemory.current[tab] = event.currentTarget.scrollTop;
          }}
        >
          {renderRoot()}
        </div>
        <div className="detail-pane" ref={detailPaneRef} tabIndex={-1}>
          {playerPane ? playerSheet : renderDetail()}
        </div>
      </div>
    ) : book !== null ? (
      <div
        className="detail-pane detail-full"
        ref={detailPaneRef}
        tabIndex={-1}
      >
        {renderDetail()}
      </div>
    ) : (
      renderRoot()
    );

  return (
    <div
      className="app"
      data-layout={layout}
      data-player-pane={playerPane ? "true" : "false"}
    >
      <audio ref={audioRef} preload="metadata" style={{ display: "none" }} />
      <div className="app-frame">
        {layout === "expanded" && (
          <div
            className={
              leadingMiniPlayer
                ? "app-leading app-leading-with-mini"
                : "app-leading"
            }
          >
            <nav
              className="nav-rail"
              role="tablist"
              aria-orientation="vertical"
              aria-label="Слухайка"
            >
              {ROOT_TABS.map((id) => (
                <button
                  key={id}
                  type="button"
                  role="tab"
                  className="nav-rail-item"
                  aria-selected={tab === id && book === null}
                  onClick={() => selectRoot(id)}
                >
                  <span className="nav-rail-icon" aria-hidden="true">
                    {TAB_ICONS[id]}
                  </span>
                  <span className="nav-rail-label">{t(TAB_LABELS[id])}</span>
                </button>
              ))}
            </nav>
            {leadingMiniPlayer && (
              <MiniPlayer
                placement="rail"
                engine={engine}
                onExpand={() => setPlayerOpen(true)}
              />
            )}
          </div>
        )}
        <div className="app-content">
          <header className="app-header">
            {book !== null ? (
              <button className="back" onClick={closeBook}>
                {t("back")}
              </button>
            ) : tab === "settings" ? (
              <button className="back" onClick={leaveSettings}>
                {t("back")}
              </button>
            ) : (
              <div className="app-header-titles">
                <h1 className="app-title">Слухайка</h1>
                <p className="app-subtitle">{t("appSubtitle")}</p>
              </div>
            )}
            <div className="app-header-actions">
              {book === null && tab !== "settings" && (
                <button
                  type="button"
                  className="gear"
                  data-settings-entry="true"
                  onClick={openSettings}
                  aria-label={t("tabSettings")}
                  title={t("tabSettings")}
                >
                  {TAB_ICONS.settings}
                </button>
              )}
              <button
                type="button"
                onClick={() => setUiLocale(locale === "uk" ? "en" : "uk")}
                aria-label={t("langSwitchAria")}
                className="lang-switch"
              >
                {locale === "uk" ? "EN" : "UA"}
              </button>
            </div>
          </header>
          <main
            className={`surface${split ? " surface-split" : ""}`}
            ref={surfaceRef}
            onScroll={(event) => {
              if (book === null || split)
                scrollMemory.current[tab] = event.currentTarget.scrollTop;
            }}
          >
            {mainContent}
          </main>
          {!leadingMiniPlayer && !playerOpen && (
            <MiniPlayer
              placement={layout === "compact" ? "compact" : "bottom"}
              engine={engine}
              onExpand={() => setPlayerOpen(true)}
            />
          )}
        </div>
      </div>
      {playerOpen && !playerPane && playerSheet}
      {layout === "compact" && book === null && !playerOpen && (
        <nav className="tab-bar" role="tablist">
          {SELECTED_TAB_ORDER.map((id) => (
            <button
              key={id}
              type="button"
              role="tab"
              aria-selected={tab === id}
              onClick={() => selectRoot(id)}
            >
              {t(TAB_LABELS[id])}
            </button>
          ))}
        </nav>
      )}
    </div>
  );
}
