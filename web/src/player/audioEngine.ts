/**
 * spec-43/T5 — binding between PlaybackEngine and a single HTMLAudioElement.
 * The engine owns state/timing; this layer mirrors attempt URL onto audio.src,
 * forwards element events back to the engine, and wires Media Session + local
 * persistence (via injected storage). One singleton audio element for the app
 * lifetime — matches Android's single Player for MediaSession.
 */
import { PlaybackEngine, type EngineState } from './engine'
import { updateNowPlaying } from './mediaSession'
import { LocalListeningStateStore, type StorageLike, type LocalListeningStateSnapshot } from './localState'
import { rewoundPositionMs } from './smartRewind'
import type { Chapter } from '../worker/types'
import type { ProgressSyncController } from '../sync/controller'
import {
  extendSleepTimer,
  isSleepTimerActive,
  rearmEndOfChapterTimer,
  setSleepTimer,
  SLEEP_TIMER_OFF,
  sleepTimerFadeVolume,
  tickSleepTimer,
  type SleepTimerState,
} from './sleepTimer'
import type { PlayerBookmarksStore } from './bookmarks'

/**
 * Android's auto-bookmark note, verbatim (AudioPlayerManager.kt writes the
 * same string for the timer-stop bookmark — shown as-is in the list, like
 * Android, regardless of interface language).
 */
export const AUTO_BOOKMARK_NOTE = 'Авто-закладка (Таймер сну)'

export interface AudioEngineOptions {
  relayBase?: string
  storage?: StorageLike
  store?: LocalListeningStateStore
  syncController?: ProgressSyncController | null
  bookmarks?: PlayerBookmarksStore | null
}

export class AudioEngine {
  readonly engine: PlaybackEngine
  private audio: HTMLAudioElement | null = null
  private store: LocalListeningStateStore
  private syncController: ProgressSyncController | null
  private editionId: string | undefined
  private bookTitle = ''
  private chapters: Chapter[] = []
  private ticker: ReturnType<typeof setInterval> | null = null
  private lastPersistMs = 0
  private relayBase: string | undefined
  private bookmarks: PlayerBookmarksStore | null
  private workId: string | undefined

  // W5.1 — the sleep timer (Android's AudioPlayerManager policy): its own
  // wall-clock interval, independent of the play ticker, because Android's
  // CountDownTimer keeps counting even while the book is paused. The pure
  // rules live in sleepTimer.ts; this layer only feeds seconds and applies
  // the fade.
  private sleepTimer: SleepTimerState = SLEEP_TIMER_OFF
  private sleepTimerInterval: ReturnType<typeof setInterval> | null = null
  private readonly sleepTimerListeners = new Set<(state: SleepTimerState) => void>()
  private lastChapterIndex = -1

  constructor(opts: AudioEngineOptions = {}) {
    this.relayBase = opts.relayBase
    this.syncController = opts.syncController ?? null
    this.bookmarks = opts.bookmarks ?? null
    this.engine = new PlaybackEngine({
      relayUrlOf: this.relayBase ? (url) => relayUrlFor(this.relayBase!, url) : undefined,
    })
    if (opts.store) {
      this.store = opts.store
    } else {
      const storageLike: StorageLike = opts.storage ?? {
        getItem: (k: string) => window.localStorage.getItem(k),
        setItem: (k: string, v: string) => window.localStorage.setItem(k, v),
        removeItem: (k: string) => window.localStorage.removeItem(k),
      }
      this.store = new LocalListeningStateStore(storageLike)
    }
    this.engine.subscribe((state) => this.onEngineState(state))
  }

  setSyncController(controller: ProgressSyncController | null): void {
    this.syncController = controller
  }

  attachAudio(audio: HTMLAudioElement): void {
    this.audio = audio
    audio.addEventListener('playing', () => this.engine.attemptPlaying())
    audio.addEventListener('error', () => this.engine.attemptErrored())
    audio.addEventListener('ended', () => this.onEnded())
  }

  async loadBook(
    detail: { title: string; chapters: Chapter[]; editionId?: string; workId?: string },
    startChapter = 0,
    opts: { forceChapter?: boolean; startPositionSeconds?: number } = {},
  ): Promise<void> {
    this.bookTitle = detail.title
    this.chapters = detail.chapters
    this.editionId = detail.editionId ?? detail.title
    this.workId = detail.workId
    // Progress Sync: pull the cloud state before resuming (LWW).
    if (this.syncController && this.editionId) {
      try {
        await this.syncController.pullBeforeResume(this.editionId)
      } catch {
        // degrade-never
      }
    }
    let startPosition = opts.startPositionSeconds ?? 0
    let chapterIndex = startChapter
    // An explicit jump (bookmark, chapter pick) is the user's expressed
    // intent — the saved state must not override it.
    if (!opts.forceChapter) {
      const saved = this.editionId ? this.store.load(this.editionId) : null
      if (saved && saved.chapterIndex < detail.chapters.length) {
        chapterIndex = saved.chapterIndex
        const pausedFor = saved.lastPausedAtEpochMs ? Date.now() - saved.lastPausedAtEpochMs : 0
        startPosition = pausedFor > 0 ? rewoundPositionMs(saved.positionSeconds, pausedFor) : saved.positionSeconds
      }
    }
    this.engine.load(detail.chapters, {
      startChapter: chapterIndex,
      startPositionSeconds: startPosition,
      editionId: this.editionId,
    })
    this.lastChapterIndex = chapterIndex
    this.syncAudioSrc()
    this.engine.play()
    void this.audio?.play().catch(() => {})
    this.startTicker()
    this.rearmSleepTimerForChapter()
    this.updateSession()
  }

  /**
   * Resolves only when the real media element emits `playing`. A successful
   * catalogue action must never be inferred from a fetched book page or from
   * assigning `audio.src`; the direct→relay fallback remains inside this one
   * bounded user attempt.
   */
  loadBookAndAwaitPlaying(
    detail: { title: string; chapters: Chapter[]; editionId?: string; workId?: string },
    startChapter = 0,
    timeoutMs = 8_000,
  ): Promise<boolean> {
    const audio = this.audio
    if (!audio) return Promise.resolve(false)
    return new Promise((resolve) => {
      let done = false
      let unsubscribe = () => {}
      const finish = (playing: boolean): void => {
        if (done) return
        done = true
        clearTimeout(timeout)
        audio.removeEventListener('playing', onPlaying)
        unsubscribe()
        if (!playing) this.pause()
        resolve(playing)
      }
      const onPlaying = (): void => finish(true)
      const timeout = setTimeout(() => finish(false), timeoutMs)
      audio.addEventListener('playing', onPlaying)
      unsubscribe = this.subscribe((state) => {
        if (state.status === 'unavailable') finish(false)
      })
      void this.loadBook(detail, startChapter).catch(() => finish(false))
    })
  }

  play(): void {
    this.engine.play()
    this.syncAudioSrc()
    void this.audio?.play().catch(() => {})
    this.startTicker()
  }

  pause(): void {
    this.engine.pause()
    this.audio?.pause()
    this.persist(true)
    this.stopTicker()
  }

  seek(seconds: number): void {
    this.engine.seek(seconds)
    if (this.audio) this.audio.currentTime = seconds
    this.persist(true)
  }

  setSpeed(speed: number): void {
    this.engine.setSpeed(speed)
    if (this.audio) this.audio.playbackRate = speed
    this.persist(true)
  }

  skip(deltaSeconds: number): void {
    const s = this.engine.getState()
    this.seek(s.positionSeconds + deltaSeconds)
  }

  nextChapter(): void {
    const s = this.engine.getState()
    if (s.chapterIndex < this.chapters.length - 1) {
      this.loadBook({ title: this.bookTitle, chapters: this.chapters, editionId: this.editionId, workId: this.workId }, s.chapterIndex + 1)
    }
  }

  prevChapter(): void {
    const s = this.engine.getState()
    if (s.chapterIndex > 0) {
      this.loadBook({ title: this.bookTitle, chapters: this.chapters, editionId: this.editionId, workId: this.workId }, s.chapterIndex - 1)
    } else {
      this.seek(0)
    }
  }

  /** W5.1 — a bookmark jump: the user's expressed intent, never overridden. */
  jumpTo(chapterIndex: number, positionSeconds: number): void {
    const s = this.engine.getState()
    if (s.chapterIndex === chapterIndex) {
      this.seek(positionSeconds)
      return
    }
    void this.loadBook(
      { title: this.bookTitle, chapters: this.chapters, editionId: this.editionId, workId: this.workId },
      chapterIndex,
      { forceChapter: true, startPositionSeconds: positionSeconds },
    )
  }

  /** W5.1 — the loaded chapters, for the player's chapter list. */
  chaptersOf(): Chapter[] {
    return this.chapters
  }

  /** W5.1 — the sleep timer controls (Android's option vocabulary). */
  setSleepTimer(minutes: number): void {
    if (minutes === -1) {
      const remaining = this.chapterRemainingSeconds()
      if (remaining === null || remaining <= 0) {
        // No honest chapter boundary (unknown duration) — refuse rather
        // than fabricate a stop point.
        this.clearSleepTimer()
        return
      }
      this.sleepTimer = setSleepTimer(-1, remaining)
    } else {
      this.sleepTimer = setSleepTimer(minutes, null)
    }
    this.syncSleepTimerInterval()
    this.publishSleepTimer()
  }

  extendSleepTimer(): void {
    this.sleepTimer = extendSleepTimer(this.sleepTimer)
    this.syncSleepTimerInterval()
    this.publishSleepTimer()
  }

  getSleepTimerState(): SleepTimerState {
    return this.sleepTimer
  }

  subscribeSleepTimer(listener: (state: SleepTimerState) => void): () => void {
    this.sleepTimerListeners.add(listener)
    return () => {
      this.sleepTimerListeners.delete(listener)
    }
  }

  getState(): EngineState {
    return this.engine.getState()
  }

  /** W5.1 — the current Work's mergeKey, for the bookmarks list grouping. */
  workIdOf(): string | undefined {
    return this.workId
  }

  subscribe(listener: (state: EngineState) => void): () => void {
    return this.engine.subscribe(listener)
  }

  private syncAudioSrc(): void {
    if (!this.audio) return
    const state = this.engine.getState()
    const chapter = this.chapters[state.chapterIndex]
    if (!chapter) return
    const url =
      state.attemptKind === 'relay' && this.relayBase
        ? `${this.relayBase}/audio?u=${encodeURIComponent(chapter.streamUrl)}`
        : chapter.streamUrl
    if (this.audio.src !== url) {
      this.audio.src = url
      this.audio.currentTime = state.positionSeconds
      this.audio.playbackRate = state.speed
    }
  }

  private onEngineState(state: EngineState): void {
    if (state.attemptKind === 'relay' && this.audio && !this.audio.src.includes('/api/audio')) {
      this.syncAudioSrc()
      void this.audio.play().catch(() => {})
    }
    this.updateSession()
    if (state.status === 'paused' || state.status === 'unavailable') {
      this.persist(true)
    }
  }

  private onEnded(): void {
    const state = this.engine.getState()
    if (state.chapterIndex < this.chapters.length - 1) {
      void this.loadBook({ title: this.bookTitle, chapters: this.chapters, editionId: this.editionId, workId: this.workId }, state.chapterIndex + 1)
    } else {
      this.persist(true)
    }
  }

  private startTicker(): void {
    this.stopTicker()
    this.ticker = globalThis.setInterval(() => {
      this.engine.tick(1000)
      const state = this.engine.getState()
      if (state.chapterIndex !== this.lastChapterIndex) {
        // Auto-advance (or any engine-side chapter change): an end-of-
        // chapter sleep timer re-arms at the NEW chapter's remainder.
        this.lastChapterIndex = state.chapterIndex
        this.rearmSleepTimerForChapter()
      }
      if (this.audio && state.status === 'playing' && Math.abs(this.audio.currentTime - state.positionSeconds) > 1) {
        this.audio.currentTime = state.positionSeconds
      }
      if (Date.now() - this.lastPersistMs > 30000) this.persist(false)
    }, 1000)
  }

  private stopTicker(): void {
    if (this.ticker !== null) {
      clearInterval(this.ticker)
      this.ticker = null
    }
  }

  // ---- W5.1 sleep timer transport -------------------------------------

  private chapterRemainingSeconds(): number | null {
    const s = this.engine.getState()
    const chapter = this.chapters[s.chapterIndex]
    if (!chapter || typeof chapter.durationSeconds !== 'number' || chapter.durationSeconds <= 0) return null
    return Math.max(0, Math.round(chapter.durationSeconds - s.positionSeconds))
  }

  private syncSleepTimerInterval(): void {
    if (isSleepTimerActive(this.sleepTimer)) {
      if (this.sleepTimerInterval === null) {
        // Android's CountDownTimer keeps running while paused — so does this.
        this.sleepTimerInterval = globalThis.setInterval(() => {
          this.sleepTimer = tickSleepTimer(this.sleepTimer)
          if (this.audio) this.audio.volume = sleepTimerFadeVolume(this.sleepTimer.remainingSeconds)
          this.publishSleepTimer()
          if (!isSleepTimerActive(this.sleepTimer)) this.onSleepTimerFired()
        }, 1000)
      }
    } else {
      this.stopSleepTimerInterval()
    }
  }

  private stopSleepTimerInterval(): void {
    if (this.sleepTimerInterval !== null) {
      clearInterval(this.sleepTimerInterval)
      this.sleepTimerInterval = null
    }
  }

  private clearSleepTimer(): void {
    this.stopSleepTimerInterval()
    if (this.audio) this.audio.volume = 1
    this.sleepTimer = { ...SLEEP_TIMER_OFF }
    this.publishSleepTimer()
  }

  private onSleepTimerFired(): void {
    this.stopSleepTimerInterval()
    if (this.audio) this.audio.volume = 1
    // Android's onFinish: the auto-bookmark lands first, then the pause
    // (recording the honest TIMER_STOP moment), then the state collapses.
    if (this.bookmarks && this.editionId) {
      const s = this.engine.getState()
      const chapter = this.chapters[s.chapterIndex]
      void this.bookmarks
        .add({
          workId: this.workId ?? this.editionId,
          editionId: this.editionId,
          chapterIndex: s.chapterIndex,
          chapterTitle: chapter?.title ?? `Розділ ${s.chapterIndex + 1}`,
          timestampSeconds: Math.floor(s.positionSeconds),
          note: AUTO_BOOKMARK_NOTE,
        })
        .catch(() => {})
    }
    this.pause()
    this.sleepTimer = { ...SLEEP_TIMER_OFF }
    this.publishSleepTimer()
  }

  private rearmSleepTimerForChapter(): void {
    if (!this.sleepTimer.isEndOfChapter || !isSleepTimerActive(this.sleepTimer)) return
    const remaining = this.chapterRemainingSeconds()
    if (remaining !== null) {
      this.sleepTimer = rearmEndOfChapterTimer(this.sleepTimer, remaining)
      this.publishSleepTimer()
    }
  }

  private publishSleepTimer(): void {
    for (const listener of this.sleepTimerListeners) {
      listener(this.sleepTimer)
    }
  }

  private persist(immediate = false): void {
    if (!this.editionId) return
    const s = this.engine.getState()
    const snapshot: LocalListeningStateSnapshot = {
      editionId: this.editionId,
      chapterIndex: s.chapterIndex,
      positionSeconds: s.positionSeconds,
      isCompleted: s.isCompleted,
      preferredSpeed: s.speed,
      lastPausedAtEpochMs: s.status === 'paused' ? Date.now() : null,
    }
    this.store.save(snapshot)
    this.lastPersistMs = Date.now()
    // Progress Sync: mirror to cloud when bound and enabled (LWW, throttled).
    if (this.syncController) {
      void this.syncController.pushAfterSave(this.editionId, immediate).catch(() => {})
    }
  }

  private updateSession(): void {
    const s = this.engine.getState()
    if (s.status === 'idle' || this.chapters.length === 0) return
    const chapter = this.chapters[s.chapterIndex]
    updateNowPlaying(
      {
        title: chapter?.title ?? this.bookTitle,
        author: this.bookTitle,
        chapterTitle: `Розділ ${s.chapterIndex + 1} з ${this.chapters.length}`,
      },
      {
        onPlay: () => this.play(),
        onPause: () => this.pause(),
        onPreviousTrack: () => this.prevChapter(),
        onNextTrack: () => this.nextChapter(),
      },
    )
  }
}

/** A source query may be a signed locator; relay only clean public URLs. */
export function relayUrlFor(relayBase: string, streamUrl: string): string {
  try {
    if (new URL(streamUrl).search.length > 0) return ''
    return `${relayBase}/audio?u=${encodeURIComponent(streamUrl)}`
  } catch {
    return ''
  }
}
