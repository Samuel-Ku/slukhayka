/**
 * spec-43/T5 (pre-work engine slice) — the framework-free playback store for
 * the Web Client: a plain class with a subscribe callback, no React, no DOM.
 * The thin `<audio>` binding that feeds real events arrives with T5; this
 * engine owns state, timing and the two pure policies (ADR-0003 Smart
 * Rewind via `smartRewind`, ADR-0019-spirit attempt budget via
 * `fallbackPolicy`).
 *
 * Determinism: all wall-clock reads go through an injected `PlayerClock`,
 * and time itself advances only through explicit `tick(deltaMs)` calls —
 * tests drive both.
 *
 * Semantics worth pinning:
 * - In-session Smart Rewind (ADR-0003): resuming a pause longer than the
 *   tiers allow rewinds the live position via the ONE rule; an unchanged
 *   target skips the rewind. The pause marker clears on load/seek — one
 *   pause never rewinds twice, an expressed seek intent is never undone.
 * - Auto-advance: when a chapter with a known duration runs out, playback
 *   moves to the next chapter at zero as a fresh prepare (its own attempt
 *   budget). The LAST chapter's end parks on 'paused' at the chapter's
 *   duration with `isCompleted = true` in the published payload.
 * - Attempts: `play()` after idle/load/unavailable begins a user-initiated
 *   prepare (fresh DIRECT attempt); pause/resume within one prepare keeps
 *   it. `attemptErrored()` / `attemptPlaying()` feed the fallback policy;
 *   the engine exposes `attemptKind` but never touches media elements.
 */

import type { Chapter } from '../worker/types'
import { decideNext, type Attempt } from './fallbackPolicy'
import { rewoundPositionMs } from './smartRewind'

export interface PlayerClock {
  nowMs(): number
}

export type PlaybackStatus = 'idle' | 'loading' | 'playing' | 'paused' | 'unavailable'

export interface EngineState {
  status: PlaybackStatus
  editionId?: string
  chapterIndex: number
  positionSeconds: number
  speed: number
  attemptKind?: Attempt['kind']
  isCompleted: boolean
}

export interface LoadOptions {
  startChapter?: number
  startPositionSeconds?: number
  editionId?: string
}

export type RelayUrlOf = (url: string) => string

export class PlaybackEngine {
  private chapters: Chapter[] = []
  private readonly clock: PlayerClock
  private readonly relayUrlOf: RelayUrlOf
  private readonly listeners = new Set<(state: EngineState) => void>()

  private status: PlaybackStatus = 'idle'
  private editionId: string | undefined
  private chapterIndex = 0
  private positionSeconds = 0
  private speed = 1
  private isCompleted = false
  private attempt: Attempt | null = null
  private pausedAtMs: number | null = null

  constructor(opts?: { clock?: PlayerClock; relayUrlOf?: RelayUrlOf }) {
    this.clock = opts?.clock ?? { nowMs: () => Date.now() }
    this.relayUrlOf = opts?.relayUrlOf ?? (() => '')
  }

  load(chapters: Chapter[], opts: LoadOptions = {}): void {
    if (chapters.length === 0) return
    this.chapters = chapters
    this.editionId = opts.editionId
    this.chapterIndex = Math.max(0, Math.min(opts.startChapter ?? 0, chapters.length - 1))
    this.positionSeconds = Math.max(0, opts.startPositionSeconds ?? 0)
    this.isCompleted = false
    this.attempt = null
    this.pausedAtMs = null
    this.status = 'loading'
    this.publish()
  }

  play(): void {
    if (this.status === 'playing' || this.status === 'idle' || this.chapters.length === 0) return
    if (this.status === 'paused' && this.pausedAtMs !== null) {
      const pausedForMs = this.clock.nowMs() - this.pausedAtMs
      const rewound = rewoundPositionMs(this.positionSeconds, pausedForMs)
      if (rewound !== this.positionSeconds) {
        this.positionSeconds = rewound
      }
      this.pausedAtMs = null
    }
    if (this.attempt === null) {
      const decision = decideNext(null, { type: 'started' }, this.directUrl(), this.relayUrlOf)
      if ('giveUp' in decision || decision.next === null) return
      this.attempt = decision.next
    }
    this.status = 'playing'
    this.publish()
  }

  pause(): void {
    if (this.status !== 'playing') return
    this.status = 'paused'
    this.pausedAtMs = this.clock.nowMs()
    this.publish()
  }

  seek(seconds: number): void {
    if (this.chapters.length === 0) return
    const duration = this.currentChapter()?.durationSeconds
    const upperBound = duration !== undefined ? duration : Number.POSITIVE_INFINITY
    this.positionSeconds = Math.max(0, Math.min(seconds, upperBound))
    this.pausedAtMs = null
    this.publish()
  }

  setSpeed(speed: number): void {
    if (!Number.isFinite(speed) || speed <= 0) return
    this.speed = speed
    this.publish()
  }

  tick(deltaMs: number): void {
    if (this.status !== 'playing') return
    this.positionSeconds += (deltaMs / 1000) * this.speed
    const duration = this.currentChapter()?.durationSeconds
    if (duration === undefined || this.positionSeconds < duration) {
      this.publish()
      return
    }
    if (this.chapterIndex < this.chapters.length - 1) {
      this.chapterIndex += 1
      this.positionSeconds = 0
      this.isCompleted = false
      this.attempt = null
      const decision = decideNext(null, { type: 'started' }, this.directUrl(), this.relayUrlOf)
      if (!('giveUp' in decision) && decision.next !== null) {
        this.attempt = decision.next
      }
    } else {
      this.positionSeconds = duration
      this.status = 'paused'
      this.isCompleted = true
      this.attempt = null
      this.pausedAtMs = null
    }
    this.publish()
  }

  /** A media-element error for the current attempt (the T5 binding calls this). */
  attemptErrored(): void {
    const decision = decideNext(this.attempt, { type: 'error' }, this.directUrl(), this.relayUrlOf)
    if ('giveUp' in decision) {
      this.attempt = null
      this.status = 'unavailable'
    } else {
      this.attempt = decision.next
    }
    this.publish()
  }

  /** The media element actually started producing sound for the current attempt. */
  attemptPlaying(): void {
    const decision = decideNext(this.attempt, { type: 'playing' }, this.directUrl(), this.relayUrlOf)
    if (!('giveUp' in decision)) {
      this.attempt = decision.next
      this.publish()
    }
  }

  subscribe(listener: (state: EngineState) => void): () => void {
    this.listeners.add(listener)
    return () => {
      this.listeners.delete(listener)
    }
  }

  getState(): EngineState {
    return {
      status: this.status,
      editionId: this.editionId,
      chapterIndex: this.chapters.length > 0 ? this.chapterIndex : 0,
      positionSeconds: this.positionSeconds,
      speed: this.speed,
      attemptKind: this.attempt?.kind,
      isCompleted: this.isCompleted,
    }
  }

  private currentChapter(): Chapter | undefined {
    return this.chapters[this.chapterIndex]
  }

  private directUrl(): string {
    return this.currentChapter()?.streamUrl ?? ''
  }

  private publish(): void {
    const snapshot = this.getState()
    for (const listener of this.listeners) {
      listener(snapshot)
    }
  }
}
