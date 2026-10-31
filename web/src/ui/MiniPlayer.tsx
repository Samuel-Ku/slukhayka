import { useEffect, useState, type CSSProperties } from "react";
import type { AudioEngine } from "../player/audioEngine";
import type { EngineState } from "../player/engine";
import { useTranslate } from "../i18n/locale";
import { useOnline } from "../offline/status";

export function MiniPlayer({
  engine,
  onExpand,
  placement = "compact",
}: {
  engine: AudioEngine;
  onExpand: () => void;
  /** #1236 — the full-width strip, or the leading column under the rail. */
  placement?: "compact" | "bottom" | "rail";
}) {
  const t = useTranslate();
  const online = useOnline();
  const [state, setState] = useState<EngineState>(engine.getState());
  useEffect(() => engine.subscribe(setState), [engine]);
  if (state.status === "idle") return null;
  const isPlaying = state.status === "playing";
  const placementStyle: CSSProperties =
    placement === "rail"
      ? {
          position: "static",
          width: "100%",
          marginTop: "auto",
          background: "var(--surface)",
          borderTop: "1px solid var(--line)",
          padding: "8px 10px",
          display: "flex",
          alignItems: "center",
          gap: 8,
        }
      : placement === "bottom"
        ? {
            position: "static",
            margin: "0 8px 8px",
            background: "var(--surface)",
            border: "1px solid var(--line)",
            borderRadius: 12,
            padding: "8px 12px",
            display: "flex",
            alignItems: "center",
            gap: 12,
          }
        : {
            position: "fixed",
            bottom: "calc(env(safe-area-inset-bottom) + 56px)",
            left: 8,
            right: 8,
            background: "var(--surface)",
            border: "1px solid var(--line)",
            borderRadius: 12,
            padding: "8px 12px",
            display: "flex",
            alignItems: "center",
            gap: 12,
            zIndex: 40,
          };
  return (
    <div
      className={`mini-player mini-player-${placement}`}
      style={placementStyle}
    >
      {/* #1689 — the ONLY door to the full player is a real button: Tab reaches
          it, Enter/Space open it, and a screen reader announces the ACTION
          instead of a bare chapter string. The strip used to be a click-only
          <div>, so keyboard and screen-reader listeners were locked out. */}
      <button
        type="button"
        className="mini-player-expand"
        onClick={onExpand}
        aria-label={t("miniOpenPlayer")}
        style={{
          flex: 1,
          display: "flex",
          alignItems: "center",
          gap: 8,
          background: "none",
          border: "none",
          color: "inherit",
          padding: 0,
          textAlign: "left",
          fontSize: 14,
          whiteSpace: "nowrap",
          overflow: "hidden",
          textOverflow: "ellipsis",
        }}
      >
        <span
          style={{
            overflow: "hidden",
            textOverflow: "ellipsis",
          }}
        >
          {t("miniChapter", {
            n: state.chapterIndex + 1,
            time: `${Math.floor(state.positionSeconds / 60)}:${String(Math.floor(state.positionSeconds % 60)).padStart(2, "0")}`,
          })}
          {!online && <span className="offline-chip">{t("offline")}</span>}
        </span>
      </button>
      <button
        type="button"
        className="mini-transport"
        aria-label={isPlaying ? t("miniPause") : t("miniPlay")}
        onClick={() => (isPlaying ? engine.pause() : engine.play())}
        style={{
          background: "var(--accent)",
          color: "#000",
          border: "none",
          borderRadius: 999,
        }}
      >
        {isPlaying ? "⏸" : "▶"}
      </button>
      <button
        type="button"
        className="mini-transport"
        aria-label={t("miniSeekForward")}
        onClick={() => engine.skip(15)}
        style={{ background: "none", border: "none", color: "var(--fg-dim)" }}
      >
        +15s
      </button>
    </div>
  );
}
