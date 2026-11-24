import {
  useEffect,
  useMemo,
  useRef,
  useState,
  type CSSProperties,
} from "react";
import type { AudioEngine } from "../player/audioEngine";
import type { EngineState } from "../player/engine";
import { useTranslate } from "../i18n/locale";
import type { ListenerProfile } from "../identity/listenerIdentity";
import type { ReviewsStore } from "../reviews/store";
import type { PlayerBookmarksStore } from "../player/bookmarks";
import { editionIdFor, mergeKeyFor } from "../sync/edition";
import { reviewWorkIdFor, ListenerReviewFormSheet } from "./bookReviews";
import type { ListenerReview } from "../reviews/reviewModel";
import {
  BookmarksPane,
  ChapterList,
  formatClock,
  SleepTimerPane,
} from "./playerExtras";
import { useOnline } from "../offline/status";
import { trapFocus } from "./focusTrap";

const SPEED_MIN = 0.5;
const SPEED_MAX = 3;
const SPEED_STEP = 0.05;
const SPEED_PRESETS = [0.75, 1, 1.25, 1.5, 1.75, 2];

function formatSpeed(speed: number): string {
  return String(Math.round(speed * 100) / 100);
}

export type PlayerTab = "chapters" | "timer" | "bookmarks";

/** Is the event target inside a DIFFERENT dialog than the player overlay? */
export function isNestedDialogTarget(
  target: EventTarget | null,
  root: HTMLElement | null
): boolean {
  if (root === null || !(target instanceof Element)) return false;
  const dialog = target.closest('[role="dialog"]');
  return dialog !== null && dialog !== root;
}

export function PlayerSheet({
  engine,
  onClose,
  lastPlayed,
  profile,
  reviewsStore,
  bookmarksStore,
  placement = "overlay",
}: {
  engine: AudioEngine;
  onClose: () => void;
  /** W4.1 — the Work the player last loaded, for the finish prompt's review form. */
  lastPlayed: {
    title: string;
    author: string;
    narrator?: string;
    language?: string;
    url: string;
  } | null;
  profile: ListenerProfile | null;
  reviewsStore: ReviewsStore | null;
  bookmarksStore: PlayerBookmarksStore | null;
  /**
   * #1236 — `pane` is the right-hand column of a window ≥840 px (the page it
   * replaces is NOT drawn twice); `overlay` is the full-screen surface below
   * that line. The content is the same in both; only the placement differs.
   */
  placement?: "overlay" | "pane";
}) {
  const t = useTranslate();
  const [state, setState] = useState<EngineState>(engine.getState());
  const [tab, setTab] = useState<PlayerTab>("chapters");
  const [finishFormOpen, setFinishFormOpen] = useState(false);
  const [bookmarkCount, setBookmarkCount] = useState(0);
  const online = useOnline();
  const [cachedUrls, setCachedUrls] = useState<ReadonlySet<string>>(new Set());
  useEffect(() => engine.subscribe(setState), [engine]);

  const isPlaying = state.status === "playing";
  const finished =
    state.isCompleted && lastPlayed !== null && reviewsStore !== null;
  const speedOptions = SPEED_PRESETS.includes(state.speed)
    ? SPEED_PRESETS
    : [...SPEED_PRESETS, state.speed].sort((a, b) => a - b);

  const chapters = engine.chaptersOf();
  const workId = engine.workIdOf();
  // #1686 — the seek slider must span the REAL chapter duration. The old
  // `max={100}` was a literal, so on any chapter longer than 1:40 the browser
  // clamped both the thumb and every drag to the first 100 seconds. An unknown
  // duration is not a range: hiding the slider is honest (ADR-0014/0059),
  // guessing one would lie about where the listener can seek.
  const chapterDurationSeconds = chapters[state.chapterIndex]?.durationSeconds;
  const seekDurationSeconds =
    typeof chapterDurationSeconds === "number" &&
    Number.isFinite(chapterDurationSeconds) &&
    chapterDurationSeconds > 0
      ? chapterDurationSeconds
      : null;
  const seekPositionSeconds =
    seekDurationSeconds === null
      ? 0
      : Math.min(Math.max(state.positionSeconds, 0), seekDurationSeconds);

  // W6.2 — the honest «у кеші» badges: read from the REAL offline cache,
  // never guessed; refreshed when the loaded book changes.
  useEffect(() => {
    let alive = true;
    void engine.cachedStreamUrls().then((urls) => {
      if (alive) setCachedUrls(urls);
    });
    return () => {
      alive = false;
    };
  }, [engine, chapters]);

  // W5.1 — the honest count on the tab label from the start (Android shows
  // it before the pane opens); the pane refreshes it after every change.
  useEffect(() => {
    if (bookmarksStore === null || workId === undefined) {
      setBookmarkCount(0);
      return;
    }
    void bookmarksStore
      .forWork(workId)
      .then((list) => setBookmarkCount(list.length));
  }, [bookmarksStore, workId]);

  // W5.1 — the bookmark context: the engine knows the exact rendition and
  // position; the chapter list supplies the current chapter's title. A
  // player with nothing loaded has no context and shows the honest empty.
  const bookmarkContext = useMemo(() => {
    if (workId === undefined || state.editionId === undefined) return null;
    const chapter = chapters[state.chapterIndex];
    return {
      workId,
      editionId: state.editionId,
      bookTitle: lastPlayed?.title ?? "",
      chapterIndex: state.chapterIndex,
      chapterTitle:
        chapter?.title ?? `${t("chapter", { n: state.chapterIndex + 1 })}`,
      positionSeconds: state.positionSeconds,
    };
  }, [
    workId,
    state.editionId,
    state.chapterIndex,
    state.positionSeconds,
    chapters,
    lastPlayed,
    t,
  ]);

  // W4.1 — the finish prompt's form target: the SAME ListenerReviewFormSheet
  // component the book page's block uses (one entry, one form — AC).
  const finishTarget = useMemo(() => {
    if (lastPlayed === null) return null;
    const mergeKey = mergeKeyFor(lastPlayed.title, lastPlayed.author);
    const editionId = editionIdFor(
      mergeKey,
      lastPlayed.url,
      lastPlayed.narrator ?? "",
      lastPlayed.language ?? ""
    );
    return {
      workId: reviewWorkIdFor(mergeKey, editionId),
      bookTitle: lastPlayed.title,
      defaultEditionTag: lastPlayed.narrator ?? "",
      editionOptions: [lastPlayed.narrator ?? ""],
      narrationEditionId: editionId,
    };
  }, [lastPlayed]);

  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [editingReview, setEditingReview] = useState<ListenerReview | null>(
    null
  );

  // #1688 — the full-screen overlay is a MODAL dialog: focus moves inside, Tab
  // cannot escape, Escape closes (unless a nested dialog owns it) and focus
  // returns to a still-mounted opener. The ONE history entry that makes system
  // Back close the player is owned by App (`playerOverlayHistory.ts`), not by
  // this component: StrictMode re-runs effects in dev, and a component-owned
  // history.back() in cleanup forked a stale marker that closed the player by
  // itself. The right-hand `pane` (≥840 px) stays a plain non-modal column.
  const isOverlay = placement !== "pane";
  const dialogRef = useRef<HTMLDivElement>(null);
  const closeButtonRef = useRef<HTMLButtonElement>(null);
  const openerRef = useRef<HTMLElement | null>(null);
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;

  useEffect(() => {
    if (!isOverlay) return;
    openerRef.current =
      document.activeElement instanceof HTMLElement
        ? document.activeElement
        : null;
    closeButtonRef.current?.focus();
    const onKeyDown = (event: KeyboardEvent): void => {
      if (event.key === "Escape") {
        // A dialog INSIDE the player (e.g. the review form) owns its Escape;
        // escaping it must not discard the player (and its draft) on top.
        if (isNestedDialogTarget(event.target, dialogRef.current)) return;
        event.preventDefault();
        onCloseRef.current();
      } else if (event.key === "Tab") {
        trapFocus(dialogRef.current, event);
      }
    };
    document.addEventListener("keydown", onKeyDown);
    return () => {
      document.removeEventListener("keydown", onKeyDown);
      // A still-mounted opener (e.g. a detail-pane door) gets focus back here;
      // the App-level effect covers the mini-player, which unmounts while the
      // overlay is open. History is App-owned (see playerOverlayHistory.ts) so
      // StrictMode's double effect cannot fork a stale entry.
      const opener = openerRef.current;
      if (opener !== null && opener.isConnected) opener.focus();
    };
  }, [isOverlay]);

  const rootStyle: CSSProperties =
    placement === "pane"
      ? {
          position: "static",
          background: "var(--bg)",
          display: "flex",
          flexDirection: "column",
          padding: "16px",
          minHeight: "100%",
        }
      : {
          position: "fixed",
          inset: 0,
          background: "var(--bg)",
          zIndex: 50,
          display: "flex",
          flexDirection: "column",
          padding: "16px",
          overflowY: "auto",
        };
  const headingId = "player-sheet-heading";
  const dialogProps = isOverlay
    ? ({
        role: "dialog",
        "aria-modal": true,
        "aria-labelledby": headingId,
        tabIndex: -1,
      } as const)
    : {};
  return (
    <div
      ref={dialogRef}
      className={`player-sheet player-sheet-${placement}`}
      style={rootStyle}
      data-placement={placement}
      {...dialogProps}
    >
      <button
        ref={closeButtonRef}
        onClick={onClose}
        style={{
          alignSelf: "flex-start",
          background: "none",
          border: "none",
          color: "var(--accent)",
          fontSize: 16,
        }}
      >
        {t("close")}
      </button>
      <h2 id={headingId} style={{ marginTop: 16 }}>
        {state.isCompleted
          ? t("completed")
          : t("chapter", { n: state.chapterIndex + 1 })}
      </h2>
      <p style={{ color: "var(--fg-dim)", fontSize: 14 }}>
        {t("position", { time: formatClock(state.positionSeconds) })}
        {!online && (
          <span className="offline-chip">
            {cachedUrls.has(chapters[state.chapterIndex]?.streamUrl ?? "")
              ? t("playingFromCache")
              : t("offline")}
          </span>
        )}
      </p>
      {seekDurationSeconds !== null && (
        <input
          type="range"
          min={0}
          max={seekDurationSeconds}
          step={1}
          value={seekPositionSeconds}
          onChange={(e) => engine.seek(Number(e.target.value))}
          aria-label={t("playbackPosition")}
          aria-valuetext={`${formatClock(seekPositionSeconds)} / ${formatClock(seekDurationSeconds)}`}
          style={{ width: "100%", margin: "16px 0" }}
        />
      )}
      <div style={{ display: "flex", gap: 12, alignItems: "center" }}>
        <button onClick={() => engine.skip(-15)}>⏪ 15s</button>
        <button
          onClick={() => (isPlaying ? engine.pause() : engine.play())}
          style={{
            fontSize: 24,
            padding: "8px 24px",
            borderRadius: 999,
            background: "var(--accent)",
            color: "#000",
            border: "none",
          }}
        >
          {isPlaying ? "⏸" : "▶"}
        </button>
        <button onClick={() => engine.skip(15)}>15s ⏩</button>
      </div>
      <div style={{ display: "flex", gap: 8, marginTop: 16 }}>
        <button onClick={() => engine.prevChapter()}>{t("prev")}</button>
        <button onClick={() => engine.nextChapter()}>{t("next")}</button>
      </div>
      <label
        style={{ marginTop: 16, display: "flex", alignItems: "center", gap: 8 }}
      >
        {t("speed")}
        <select
          value={state.speed}
          onChange={(e) => engine.setSpeed(Number(e.target.value))}
        >
          {speedOptions.map((s) => (
            <option key={s} value={s}>
              {formatSpeed(s)}×
            </option>
          ))}
        </select>
      </label>
      <input
        type="range"
        min={SPEED_MIN}
        max={SPEED_MAX}
        step={SPEED_STEP}
        value={state.speed}
        aria-label={t("speedExact")}
        onChange={(e) =>
          engine.setSpeed(Math.round(Number(e.target.value) * 20) / 20)
        }
        style={{ width: "100%", margin: "8px 0" }}
      />
      {state.status === "unavailable" && (
        <p style={{ color: "var(--bad)", marginTop: 12 }}>
          {t("bookUnavailable")}
        </p>
      )}

      {/* W5.1 — the three panes, Android's sheet geometry: chapters list,
          sleep timer, bookmarks. The tab bar is the modal's own, like
          Android's Розділи/Закладки tabs on the book page. */}
      <div
        className="player-tabs"
        role="tablist"
        aria-label={t("chapter", { n: state.chapterIndex + 1 })}
      >
        <button
          type="button"
          role="tab"
          aria-selected={tab === "chapters"}
          className={`player-tab${tab === "chapters" ? " player-tab-active" : ""}`}
          onClick={() => setTab("chapters")}
        >
          {t("chaptersCount", { n: chapters.length })}
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={tab === "timer"}
          className={`player-tab${tab === "timer" ? " player-tab-active" : ""}`}
          onClick={() => setTab("timer")}
        >
          {t("sleepTimerPane")}
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={tab === "bookmarks"}
          className={`player-tab${tab === "bookmarks" ? " player-tab-active" : ""}`}
          onClick={() => setTab("bookmarks")}
        >
          {t("bookmarksCount", { n: bookmarkCount })}
        </button>
      </div>

      {tab === "chapters" && (
        <div className="player-pane">
          <ChapterList
            chapters={chapters}
            currentIndex={state.chapterIndex}
            currentPosition={state.positionSeconds}
            cachedUrls={cachedUrls}
            onJump={(index) => {
              // #611 — an explicit Chapter pick starts THAT Chapter from zero,
              // the current one included: it never resumes the saved place.
              engine.jumpTo(index, 0);
            }}
          />
        </div>
      )}
      {tab === "timer" && (
        <div className="player-pane">
          <SleepTimerPane engine={engine} />
        </div>
      )}
      {tab === "bookmarks" && (
        <div className="player-pane">
          {bookmarksStore !== null ? (
            <BookmarksPane
              store={bookmarksStore}
              context={bookmarkContext}
              onJump={(bookmark) =>
                engine.jumpTo(bookmark.chapterIndex, bookmark.timestampSeconds)
              }
              onCountChange={setBookmarkCount}
            />
          ) : (
            <p className="empty-state-message">{t("noBookmarks")}</p>
          )}
        </div>
      )}

      {/* W4.1 — the finish prompt: «Книгу прослухано» opens the SAME review
          form the book page's block uses. No Firebase config → no prompt. */}
      {finished && finishTarget !== null && (
        <div
          style={{
            marginTop: 24,
            borderTop: "1px solid var(--line)",
            paddingTop: 16,
          }}
        >
          <p style={{ fontWeight: 600 }}>{t("completed")}</p>
          <button
            type="button"
            className="feed-chip"
            onClick={() => {
              setEditingReview(null);
              setSaveError(null);
              setFinishFormOpen(true);
            }}
          >
            {t("writeReview")}
          </button>
        </div>
      )}

      {finishFormOpen && finishTarget !== null && reviewsStore !== null && (
        <ListenerReviewFormSheet
          bookTitle={finishTarget.bookTitle}
          editing={editingReview}
          editionOptions={finishTarget.editionOptions}
          defaultEditionTag={finishTarget.defaultEditionTag}
          isSaving={saving}
          errorMessage={saveError}
          onSave={(rating, body, editionTag) => {
            if (profile === null) return;
            setSaving(true);
            setSaveError(null);
            void reviewsStore
              .putReview({
                workId: finishTarget.workId,
                uid: profile.uid,
                authorName: profile.nickname,
                rating,
                ...(body ? { body } : {}),
                ...(editionTag ? { editionTag } : {}),
                createdAt: editingReview?.createdAt ?? Date.now(),
                ...(editingReview ? { editedAt: Date.now() } : {}),
              })
              .then((accepted) => {
                setSaving(false);
                if (accepted) setFinishFormOpen(false);
                else setSaveError(t("reviewSaveError"));
              });
          }}
          onDismiss={() => {
            if (!saving) {
              setFinishFormOpen(false);
              setEditingReview(null);
              setSaveError(null);
            }
          }}
        />
      )}
    </div>
  );
}
