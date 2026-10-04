/**
 * Framework-free playback state and policies: Smart Rewind (ADR-0003) and
 * one direct/relay attempt budget. Actual position is observed from media
 * (ADR-0059); this store has no playback clock and never advances Chapters.
 *
 * The injected wall clock is only a pause-duration input to Smart Rewind.
 * Load/seek clear that marker so one pause never rewinds twice. Explicit
 * Chapter transitions belong to AudioEngine.loadBook(forceChapter); natural
 * end belongs to its current confirmed media generation. markCompleted is
 * accepted only at the last Chapter and parks at the actual observed end.
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

  seek(seconds: number, mediaDuration?: number): void {
    if (this.chapters.length === 0 || !Number.isFinite(seconds)) return
    const duration = mediaDuration ?? this.currentChapter()?.durationSeconds
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

  /** Actual media time is observed, never extrapolated from a timer or speed. */
  observePosition(seconds: number): void {
    if (this.chapters.length === 0 || this.isCompleted || !Number.isFinite(seconds) || seconds < 0) return
    if (this.positionSeconds === seconds) return
    this.positionSeconds = seconds
    this.publish()
  }

  /**
   * #614 — the media adapter reported the natural end of the CURRENT Chapter
   * and there is no next Chapter to prepare: the Edition is completed. Parks
   * on 'paused' at the already-observed actual media position. Catalog
   * duration cannot override an actual end, including an unknown duration.
   *
   * This is the ONE completion signal `persist()` and the finish prompt read.
   * A mid-book call is refused: completion is only ever the LAST Chapter's
   * honest end, never a shortcut out of the middle of an Edition.
   */
  markCompleted(): void {
    if (this.chapters.length === 0 || this.isCompleted) return
    if (this.chapterIndex < this.chapters.length - 1) return
    this.status = 'paused'
    this.isCompleted = true
    this.attempt = null
    this.pausedAtMs = null
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
