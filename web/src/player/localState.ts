/**
 * spec-43/T5 (pre-work engine slice) — the local facet of Listening State
 * (CONTEXT.md): progress, completion and playback preferences for ONE
 * Edition, keyed by Edition alone so a Source switch never forks progress.
 * Mirrors the Android `PlaybackProgressEntity` shape (chapterIndex,
 * positionSeconds, isCompleted, preferredSpeed, lastPausedAtEpochMs).
 *
 * Save-on-pause semantics: this store is written at the honest moments —
 * pause, seek, chapter auto-advance and completion — NEVER on playback
 * ticks. The engine publishes state on every tick; it is the T5 wiring's
 * job to call `save()` only on those moments (and once on load-restore),
 * keeping position writes off the hot path exactly like Progress Sync's
 * pacing. `lastPausedAtEpochMs` is the pause marker Smart Rewind consumes;
 * it is null until the first pause of this Edition's life.
 *
 * Storage rides an injected Storage-like interface; the browser
 * implementation wraps localStorage and corrupt payloads degrade to a miss
 * (null), never a crash.
 */

export interface LocalListeningStateSnapshot {
  editionId: string
  chapterIndex: number
  positionSeconds: number
  isCompleted: boolean
  preferredSpeed: number | null
  lastPausedAtEpochMs: number | null
}

export interface StorageLike {
  getItem(key: string): string | null
  setItem(key: string, value: string): void
  removeItem(key: string): void
}

export function listeningStateKey(editionId: string): string {
  return `slukhayka.listening.${editionId}`
}

/** The browser implementation — injectable everywhere else. */
export class BrowserStorage implements StorageLike {
  constructor(private readonly storage: Storage) {}

  getItem(key: string): string | null {
    return this.storage.getItem(key)
  }

  setItem(key: string, value: string): void {
    this.storage.setItem(key, value)
  }

  removeItem(key: string): void {
    this.storage.removeItem(key)
  }
}

function isFiniteNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value)
}

function parseSnapshot(raw: string): LocalListeningStateSnapshot | null {
  let parsed: unknown
  try {
    parsed = JSON.parse(raw)
  } catch {
    return null
  }
  if (typeof parsed !== 'object' || parsed === null) return null
  const candidate = parsed as Partial<LocalListeningStateSnapshot>
  if (
    typeof candidate.editionId !== 'string' ||
    candidate.editionId === '' ||
    !isFiniteNumber(candidate.chapterIndex) ||
    candidate.chapterIndex < 0 ||
    !Number.isInteger(candidate.chapterIndex) ||
    !isFiniteNumber(candidate.positionSeconds) ||
    candidate.positionSeconds < 0 ||
    typeof candidate.isCompleted !== 'boolean' ||
    !(candidate.preferredSpeed === null || isFiniteNumber(candidate.preferredSpeed)) ||
    !(candidate.lastPausedAtEpochMs === null || isFiniteNumber(candidate.lastPausedAtEpochMs))
  ) {
    return null
  }
  return {
    editionId: candidate.editionId,
    chapterIndex: candidate.chapterIndex,
    positionSeconds: candidate.positionSeconds,
    isCompleted: candidate.isCompleted,
    preferredSpeed: candidate.preferredSpeed,
    lastPausedAtEpochMs: candidate.lastPausedAtEpochMs,
  }
}

export class LocalListeningStateStore {
  constructor(private readonly storage: StorageLike) {}

  load(editionId: string): LocalListeningStateSnapshot | null {
    try {
      const raw = this.storage.getItem(listeningStateKey(editionId))
      if (raw === null) return null
      return parseSnapshot(raw)
    } catch {
      return null
    }
  }

  save(snapshot: LocalListeningStateSnapshot): void {
    try {
      this.storage.setItem(listeningStateKey(snapshot.editionId), JSON.stringify(snapshot))
    } catch {
      // degrade-never: private mode may refuse writes; the session keeps working
    }
  }

  clear(editionId: string): void {
    try {
      this.storage.removeItem(listeningStateKey(editionId))
    } catch {
      // nothing to undo if removal is refused
    }
  }
}
