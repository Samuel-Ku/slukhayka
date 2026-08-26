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

export interface AudioEngineOptions {
  relayBase?: string
  storage?: StorageLike
  store?: LocalListeningStateStore
  syncController?: ProgressSyncController | null
}

export class AudioEngine {
  readonly engine: PlaybackEngine
  private audio: HTMLAudioElement | null = null
  private store: LocalListeningStateStore
  private syncController: ProgressSyncController | null
  private editionId: string | undefined
  private bookTitle = ''
  private chapters: Chapter[] = []
  private ticker: number | null = null
  private lastPersistMs = 0
  private relayBase: string | undefined

  constructor(opts: AudioEngineOptions = {}) {
    this.relayBase = opts.relayBase
    this.syncController = opts.syncController ?? null
    this.engine = new PlaybackEngine({
      relayUrlOf: this.relayBase ? (url) => `${this.relayBase}/audio?u=${encodeURIComponent(url)}` : undefined,
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

  async loadBook(detail: { title: string; chapters: Chapter[]; editionId?: string }, startChapter = 0): Promise<void> {
    this.bookTitle = detail.title
    this.chapters = detail.chapters
    this.editionId = detail.editionId ?? detail.title
    // Progress Sync: pull the cloud state before resuming (LWW).
    if (this.syncController && this.editionId) {
      try {
        await this.syncController.pullBeforeResume(this.editionId)
      } catch {
        // degrade-never
      }
    }
    const saved = this.editionId ? this.store.load(this.editionId) : null
    let startPosition = 0
    let chapterIndex = startChapter
    if (saved && saved.chapterIndex < detail.chapters.length) {
      chapterIndex = saved.chapterIndex
      const pausedFor = saved.lastPausedAtEpochMs ? Date.now() - saved.lastPausedAtEpochMs : 0
      startPosition = pausedFor > 0 ? rewoundPositionMs(saved.positionSeconds, pausedFor) : saved.positionSeconds
    }
    this.engine.load(detail.chapters, {
      startChapter: chapterIndex,
      startPositionSeconds: startPosition,
      editionId: this.editionId,
    })
    this.syncAudioSrc()
    this.engine.play()
    void this.audio?.play().catch(() => {})
    this.startTicker()
    this.updateSession()
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
      this.loadBook({ title: this.bookTitle, chapters: this.chapters, editionId: this.editionId }, s.chapterIndex + 1)
    }
  }

  prevChapter(): void {
    const s = this.engine.getState()
    if (s.chapterIndex > 0) {
      this.loadBook({ title: this.bookTitle, chapters: this.chapters, editionId: this.editionId }, s.chapterIndex - 1)
    } else {
      this.seek(0)
    }
  }

  getState(): EngineState {
    return this.engine.getState()
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
      void this.loadBook({ title: this.bookTitle, chapters: this.chapters, editionId: this.editionId }, state.chapterIndex + 1)
    } else {
      this.persist(true)
    }
  }

  private startTicker(): void {
    this.stopTicker()
    this.ticker = window.setInterval(() => {
      this.engine.tick(1000)
      const state = this.engine.getState()
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
