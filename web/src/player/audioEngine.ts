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
import { OfflineAudioPrimer } from '../offline/primer'

/**
 * Android's auto-bookmark note, verbatim (AudioPlayerManager.kt writes the
 * same string for the timer-stop bookmark — shown as-is in the list, like
 * Android, regardless of interface language).
 */
export const AUTO_BOOKMARK_NOTE = 'Авто-закладка (Таймер сну)'

/**
 * #611 — how a «Play» was expressed. The catalogue card's Play carries the
 * default `resume` intent: continue this Edition's Listening State. A
 * Chapter row carries `explicitChapter: true`, meaning «start THIS Chapter
 * from zero». The distinction lives at the interface boundary so no caller
 * has to reconstruct it from the chapter index (0 is both «first Chapter»
 * and «the default one a card passes»).
 */
export interface PlayIntent {
  explicitChapter?: boolean
}

/** The AudioEngine's per-load options, derived from a `PlayIntent`. */
export interface LoadOptions {
  /** Explicit Chapter pick — never read the saved position for the start. */
  forceChapter?: boolean
  /** Explicit start position in seconds (a bookmark jump); ignored for resume. */
  startPositionSeconds?: number
}

export interface AudioEngineOptions {
  relayBase?: string
  storage?: StorageLike
  store?: LocalListeningStateStore
  syncController?: ProgressSyncController | null
  bookmarks?: PlayerBookmarksStore | null
  /** W6.2 — the offline streaming-cache primer (injectable in tests). */
  offlinePrimer?: OfflineAudioPrimer | null
}

/** A `loadBookAndAwaitPlaying` caller waiting for THIS attempt's `playing`. */
interface PlayingWaiter {
  ok(): void
  fail(): void
}

/** The listeners bound to one media session (generation) of the one element. */
interface MediaSessionListeners {
  audio: HTMLAudioElement
  onPlaying: () => void
  onError: () => void
  onEnded: () => void
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
  private offlinePrimer: OfflineAudioPrimer | null

  // ---- #617 attempt isolation ------------------------------------------
  //
  // One `<audio>` element serves the whole app, but every playback intent
  // (a load, a Next/Previous step, a relay retry) is its own attempt. The
  // engine keeps a monotonic GENERATION, binds media events to the
  // generation that armed them, and aborts the previous media session so
  // its queued `playing`/`ended`/`error` can never drive the new attempt.
  // The same generation guards a play() promise and a suspended loadBook.
  private mediaGeneration = 0
  /** The generation whose listeners are currently bound; 0 = none bound. */
  private armedGeneration = 0
  private mediaListeners: MediaSessionListeners | null = null
  /** A media `playing` confirms the attempt (headless engines count as confirmed). */
  private attemptConfirmed = true
  /** The last confirmed Listening State snapshot of the loaded Edition. */
  private prepareBaseline: LocalListeningStateSnapshot | null = null
  /** `loadBook` calls are generations too: a suspended one must not clobber a newer. */
  private loadGeneration = 0
  private readonly playingWaiters = new Set<PlayingWaiter>()
  private pendingWaiter: PlayingWaiter | null = null
  /** Set while an unconfirmed attempt is being torn down — no writes. */
  private suppressPersist = false

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
    // W6.2 — the primer only exists where a relay exists: without a relay
    // there is nothing same-origin to cache for offline playback.
    this.offlinePrimer =
      opts.offlinePrimer === undefined ? (this.relayBase ? new OfflineAudioPrimer() : null) : opts.offlinePrimer
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

  /**
   * #617 — bind the one media element. Repeated binding of the SAME element
   * is a no-op (React StrictMode mounts effects twice): listeners are bound
   * per media session by `armMediaSession`, never here, so a double attach
   * cannot multiply them. A different element supersedes the old binding.
   */
  attachAudio(audio: HTMLAudioElement): void {
    if (this.audio === audio) return
    this.detachAudio()
    this.audio = audio
    // A session already in flight (attach after a load) is re-armed on the
    // new element so its events still reach the engine.
    if (this.armedGeneration !== 0 && this.chapters.length > 0) {
      this.armMediaSession()
      this.syncAudioSrc()
    }
  }

  /**
   * #617 — unbind the media element: the current session's listeners and the
   * play ticker go away. Playback state is untouched; `attachAudio` binds
   * again.
   */
  detachAudio(): void {
    this.removeMediaListeners()
    this.audio = null
    this.armedGeneration = 0
    this.stopTicker()
  }

  /** #617 — release every background resource this engine owns. */
  dispose(): void {
    this.detachAudio()
    this.failPlayingWaiters()
    this.stopSleepTimerInterval()
  }

  async loadBook(
    detail: { title: string; chapters: Chapter[]; editionId?: string; workId?: string },
    startChapter = 0,
    opts: LoadOptions = {},
  ): Promise<boolean> {
    // #611 — the intent is decided BEFORE anything on the active audio is
    // touched: an empty Edition and an explicit Chapter outside the list are
    // honest refusals, never a silent replacement of what is playing.
    const explicit = opts.forceChapter === true
    if (detail.chapters.length === 0) return false
    if (explicit && (!Number.isInteger(startChapter) || startChapter < 0 || startChapter >= detail.chapters.length)) {
      return false
    }
    // #617 — this load is its own generation. A newer loadBook (a fast
    // Edition/Chapter switch) makes this one stale; if it resumes after an
    // awaited cloud pull it must NOT clobber the newer attempt.
    const loadGeneration = ++this.loadGeneration
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
    if (loadGeneration !== this.loadGeneration) return false
    // Explicit Chapter jump (bookmark, chapter pick) is the user's expressed
    // intent — it starts exactly there, at the given position or zero, and
    // never consults the saved place. Resume keeps the caller's fallback
    // Chapter for a listener with no history.
    const fallbackChapter = Math.max(0, Math.min(startChapter, detail.chapters.length - 1))
    let chapterIndex = explicit ? startChapter : fallbackChapter
    let startPosition = explicit ? Math.max(0, opts.startPositionSeconds ?? 0) : 0
    // The saved preferred speed belongs to the Edition, not to the place: only
    // resume restores it, so a Chapter pick keeps the pace already in force.
    const saved = explicit || !this.editionId ? null : this.store.load(this.editionId)
    if (saved) {
      if (saved.isCompleted) {
        // A finished Edition plays again from the first Chapter — at zero,
        // with the preferred speed kept (loading never resets it).
        chapterIndex = 0
        startPosition = 0
      } else if (saved.chapterIndex < detail.chapters.length) {
        // Smart Rewind is applied here and only here: once per resume, from
        // the persisted pause marker. The engine's own in-session rewind
        // cannot fire twice because a fresh load clears the pause marker.
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
    if (saved && typeof saved.preferredSpeed === 'number' && Number.isFinite(saved.preferredSpeed) && saved.preferredSpeed > 0) {
      this.engine.setSpeed(saved.preferredSpeed)
    }
    this.lastChapterIndex = chapterIndex
    // #617 — a NEW media attempt: abort the previous session, bind fresh
    // listeners to this generation, then play it in.
    this.beginAttempt()
    this.engine.play()
    this.syncAudioSrc()
    this.requestAutoplay()
    this.startTicker()
    this.rearmSleepTimerForChapter()
    this.updateSession()
    return true
  }

  /**
   * Resolves only when the real media element emits `playing` for the attempt
   * this call started. The waiter rides the attempt generation: a newer intent
   * (or a pause) fails it, and a stale `playing` from an earlier media session
   * can never resolve it — a catalogue action is never confirmed by an event
   * the attempt did not produce.
   */
  loadBookAndAwaitPlaying(
    detail: { title: string; chapters: Chapter[]; editionId?: string; workId?: string },
    startChapter = 0,
    timeoutMs = 8_000,
    opts: LoadOptions = {},
  ): Promise<boolean> {
    if (!this.audio) return Promise.resolve(false)
    return new Promise((resolve) => {
      let done = false
      let unsubscribe = () => {}
      const finish = (playing: boolean, cancel = true): void => {
        if (done) return
        done = true
        clearTimeout(timeout)
        // The waiter "owns" the attempt only while it is still parked on it:
        // a superseded waiter was already removed by `failPlayingWaiters`, so
        // its failure must never tear down the newer attempt.
        const owned = this.playingWaiters.delete(waiter) || this.pendingWaiter === waiter
        if (this.pendingWaiter === waiter) this.pendingWaiter = null
        unsubscribe()
        if (!playing && cancel && owned) this.cancelPrepare()
        resolve(playing)
      }
      const waiter: PlayingWaiter = {
        ok: () => finish(true),
        fail: () => finish(false),
      }
      // The waiter is parked as PENDING: `beginAttempt` adopts it into the
      // generation it arms, so a superseding intent fails it first.
      this.pendingWaiter = waiter
      const timeout = setTimeout(() => finish(false), timeoutMs)
      unsubscribe = this.subscribe((state) => {
        if (state.status === 'unavailable') finish(false)
      })
      // #611 — a refused load (empty Edition, explicit Chapter out of range)
      // resolves honestly at once instead of waiting out the playing budget.
      void this.loadBook(detail, startChapter, opts)
        .then((accepted) => {
          // A refused load (#611) never began an attempt: it resolves honestly
          // without tearing down whatever is currently playing.
          if (!accepted) finish(false, false)
        })
        .catch(() => finish(false, false))
    })
  }

  play(): void {
    // #617 — a play from an unloaded/unavailable engine is a fresh attempt;
    // resuming a paused one continues the SAME media session.
    const fresh = this.engine.getState().attemptKind === undefined
    this.engine.play()
    if (this.chapters.length > 0 && (fresh || this.armedGeneration === 0)) {
      this.armMediaSession()
      this.syncAudioSrc()
    }
    this.requestAutoplay()
    this.startTicker()
  }

  pause(): void {
    const unconfirmed = this.audio !== null && !this.attemptConfirmed
    // #617 — pausing also cancels a load still suspended on a cloud pull: a
    // resume must not start the sound after the listener stopped the attempt.
    this.loadGeneration += 1
    this.engine.pause()
    // Pausing before the media element ever played cancels the autoplay:
    // abort the session so a late `playing` cannot start the sound or confirm
    // the attempt. A confirmed pause keeps its buffer for resume.
    if (unconfirmed) {
      this.abortArmedSession()
    } else {
      this.audio?.pause()
    }
    this.failPlayingWaiters()
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

  /**
   * #614 — a deliberate step to the neighbouring Chapter. The step is an
   * EXPLICIT Chapter intent (`forceChapter`), so the persisted Listening
   * State snapshot can never pull the listener back to the Chapter they are
   * already leaving. On the last Chapter it is a defined no-op (Android's
   * `nextChapter` parity): the current Chapter keeps its position and state.
   */
  nextChapter(): void {
    const s = this.engine.getState()
    const next = s.chapterIndex + 1
    if (next >= this.chapters.length) return
    void this.loadBook(this.currentDetail(), next, { forceChapter: true })
  }

  /**
   * #614 — Previous steps to the previous Chapter from zero. On the FIRST
   * Chapter there is no previous one, so it restarts the current Chapter
   * (Android's `previousChapter` seeks to zero instead of leaving the
   * Edition) — a defined outcome, not a silent nothing. Same explicit intent
   * as Next, so the saved snapshot never overrides it.
   */
  prevChapter(): void {
    const s = this.engine.getState()
    if (s.chapterIndex <= 0) {
      this.seek(0)
      return
    }
    void this.loadBook(this.currentDetail(), s.chapterIndex - 1, { forceChapter: true })
  }

  /** W5.1 — a bookmark jump: the user's expressed intent, never overridden. */
  jumpTo(chapterIndex: number, positionSeconds: number): boolean {
    // #611 — an explicit Chapter outside the loaded list is refused honestly
    // rather than clamped onto a different Chapter and reported as success.
    if (!Number.isInteger(chapterIndex) || chapterIndex < 0 || chapterIndex >= this.chapters.length) return false
    const s = this.engine.getState()
    if (s.chapterIndex === chapterIndex) {
      this.seek(positionSeconds)
      return true
    }
    void this.loadBook(this.currentDetail(), chapterIndex, { forceChapter: true, startPositionSeconds: positionSeconds })
    return true
  }

  /** W5.1 — the loaded chapters, for the player's chapter list. */
  chaptersOf(): Chapter[] {
    return this.chapters
  }

  /** W6.2 — the direct stream URLs currently in the offline cache. */
  async cachedStreamUrls(): Promise<Set<string>> {
    if (this.offlinePrimer === null) return new Set()
    return this.offlinePrimer.cachedStreamUrls()
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

  /**
   * #614 — the loaded Edition's identity, so every transition path (Next,
   * Previous, ended, jumpTo) re-enters `loadBook` with exactly the same
   * description instead of four hand-copied literals.
   */
  private currentDetail(): { title: string; chapters: Chapter[]; editionId?: string; workId?: string } {
    return { title: this.bookTitle, chapters: this.chapters, editionId: this.editionId, workId: this.workId }
  }

  private primeCurrentChapter(): void {
    if (this.offlinePrimer === null || this.relayBase === undefined) return
    const state = this.engine.getState()
    const chapter = this.chapters[state.chapterIndex]
    if (!chapter) return
    const relayUrl = relayUrlFor(this.relayBase, chapter.streamUrl)
    if (relayUrl === '') return
    void this.offlinePrimer.prime(relayUrl)
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

  // ---- #617 attempt generation ----------------------------------------

  /**
   * A new user intent (load, Next/Previous, bookmark jump, a fresh play):
   * every waiter of the previous attempt is failed, the previous media
   * session is aborted, and fresh listeners are bound to a new generation.
   */
  private beginAttempt(): void {
    this.prepareBaseline = this.editionId ? this.store.load(this.editionId) : null
    // Only the PREVIOUS attempt's waiters are stale; the waiter parked for
    // this new intent is adopted by `armMediaSession` right after.
    this.failPlayingWaiters(false)
    this.armMediaSession()
  }

  /**
   * Bind the media element for ONE generation. The element is first aborted
   * (`pause` + cleared source + `load`), so the previous resource can no
   * longer deliver `playing`/`ended`/`error`; its listeners are replaced by
   * per-generation ones. Retries within one attempt (the relay fallback) call
   * this WITHOUT failing the attempt's waiters.
   */
  private armMediaSession(): number {
    this.mediaGeneration += 1
    const generation = this.mediaGeneration
    this.armedGeneration = generation
    this.attemptConfirmed = this.audio === null
    // The waiter parked for the attempt being armed now rides this
    // generation; a superseding intent would have failed it beforehand.
    if (this.pendingWaiter) {
      this.playingWaiters.add(this.pendingWaiter)
      this.pendingWaiter = null
    }
    const audio = this.audio
    if (!audio) return generation
    audio.pause()
    audio.removeAttribute('src')
    audio.load()
    const current = (): boolean => generation === this.armedGeneration
    const onPlaying = (): void => {
      if (!current()) return
      this.attemptConfirmed = true
      // The attempt produced sound: its position is legitimate from now on,
      // so a later failure must not roll the store back to the pre-attempt place.
      this.prepareBaseline = null
      this.engine.attemptPlaying()
      // W6.2 — the chapter actually started playing: prime its relay URL for
      // offline (idempotent — an already-cached chapter is not re-downloaded).
      this.primeCurrentChapter()
      this.resolvePlayingWaiters()
    }
    const onError = (): void => {
      if (!current()) return
      this.handleAttemptError()
    }
    const onEnded = (): void => {
      if (!current()) return
      this.onEnded()
    }
    audio.addEventListener('playing', onPlaying)
    audio.addEventListener('error', onError)
    audio.addEventListener('ended', onEnded)
    this.mediaListeners = { audio, onPlaying, onError, onEnded }
    return generation
  }

  private removeMediaListeners(): void {
    const bound = this.mediaListeners
    if (!bound) return
    bound.audio.removeEventListener('playing', bound.onPlaying)
    bound.audio.removeEventListener('error', bound.onError)
    bound.audio.removeEventListener('ended', bound.onEnded)
    this.mediaListeners = null
  }

  /** Abort the armed session and drop its listeners — no generation is bound. */
  private abortArmedSession(): void {
    const audio = this.audio
    if (audio) {
      audio.pause()
      audio.removeAttribute('src')
      audio.load()
    }
    this.removeMediaListeners()
    this.armedGeneration = 0
  }

  private requestAutoplay(): void {
    const audio = this.audio
    if (!audio) return
    const generation = this.armedGeneration
    let played: Promise<void> | void
    try {
      played = audio.play()
    } catch (reason) {
      this.handleAutoplayOutcome(generation, reason)
      return
    }
    void Promise.resolve(played).then(
      () => {},
      (reason) => this.handleAutoplayOutcome(generation, reason),
    )
  }

  /**
   * #617 AC4 — a browser autoplay refusal is NOT a Source failure: it must
   * not spend the attempt's one relay fallback or mark the Edition
   * unavailable. The attempt parks into the manual-Play state instead. Any
   * other rejection (a real media failure) feeds the fallback policy.
   */
  private handleAutoplayOutcome(generation: number, reason: unknown): void {
    if (generation !== this.armedGeneration) return
    if (isAutoplayRejection(reason)) {
      this.cancelPrepare()
      return
    }
    this.handleAttemptError()
  }

  private handleAttemptError(): void {
    this.engine.attemptErrored()
    const state = this.engine.getState()
    if (state.status === 'unavailable') return
    if (state.attemptKind === 'relay') {
      // The ONE relay retry is a NEW media session: the failed direct
      // attempt's queued events must not drive it, and the attempt's waiter
      // stays valid because a retry is still the same user intent.
      this.armMediaSession()
      this.syncAudioSrc()
      this.requestAutoplay()
    }
  }

  /**
   * #617 AC3 — pause/cancel of an attempt that never played. The engine parks
   * in `paused` (so the manual Play is offered) and the last CONFIRMED
   * Listening State is restored: an attempt that produced no sound must not
   * overwrite the listener's real position.
   */
  private cancelPrepare(): void {
    this.suppressPersist = true
    try {
      this.engine.pause()
      this.abortArmedSession()
      // Only THIS attempt's waiters: a newer load may be parked as pending.
      this.failPlayingWaiters(false)
      this.restoreBaseline()
      this.stopTicker()
    } finally {
      this.suppressPersist = false
    }
  }

  /** Undo an unconfirmed attempt's write, leaving the confirmed place intact. */
  private restoreBaseline(): void {
    const baseline = this.prepareBaseline
    this.prepareBaseline = null
    if (!baseline) return
    this.store.save(baseline)
    this.lastPersistMs = Date.now()
  }

  private failPlayingWaiters(includePending = true): void {
    const waiters = [...this.playingWaiters]
    this.playingWaiters.clear()
    if (includePending && this.pendingWaiter) {
      waiters.push(this.pendingWaiter)
      this.pendingWaiter = null
    }
    for (const waiter of waiters) waiter.fail()
  }

  private resolvePlayingWaiters(): void {
    const waiters = [...this.playingWaiters]
    this.playingWaiters.clear()
    for (const waiter of waiters) waiter.ok()
  }

  private onEngineState(state: EngineState): void {
    this.updateSession()
    if (state.status === 'paused') {
      this.persist(true)
    } else if (state.status === 'unavailable') {
      // #617 AC7 — a failed prepare is not a new position: undo its write
      // rather than persisting a place the listener never reached.
      this.restoreBaseline()
      this.stopTicker()
    }
  }

  /**
   * #614 — the element's natural end-of-track. The next Chapter starts from
   * zero through the same explicit intent as the Next button (never the
   * saved snapshot). The LAST Chapter has no next one, so its end IS the
   * Edition's completion — recorded even when the Source never reported a
   * duration, because the adapter's own `ended` is the end.
   */
  private onEnded(): void {
    const state = this.engine.getState()
    const next = state.chapterIndex + 1
    if (next < this.chapters.length) {
      void this.loadBook(this.currentDetail(), next, { forceChapter: true })
      return
    }
    this.engine.markCompleted()
    this.persist(true)
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
      // #617 — the periodic save only follows a CONFIRMED attempt: an
      // unattached engine (tests, headless use) has no media event to wait for.
      if ((this.audio === null || this.attemptConfirmed) && Date.now() - this.lastPersistMs > 30000) {
        this.persist(false)
      }
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
    if (this.suppressPersist) return
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

/**
 * #617 — the browser refused `play()` for lack of a user gesture. That is a
 * policy answer about the PAGE, not a failure of the Source, so it must never
 * be treated as a media error (no relay spend, no `unavailable`).
 */
export function isAutoplayRejection(reason: unknown): boolean {
  return typeof reason === 'object' && reason !== null && (reason as { name?: unknown }).name === 'NotAllowedError'
}
