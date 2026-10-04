import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AudioEngine } from '../audioEngine'
import { LocalListeningStateStore, type StorageLike } from '../localState'
import type { Chapter } from '../../worker/types'
import type { ProgressSyncController } from '../../sync/controller'

class MemoryStorage implements StorageLike {
  private values = new Map<string, string>()
  getItem(key: string): string | null { return this.values.get(key) ?? null }
  setItem(key: string, value: string): void { this.values.set(key, value) }
  removeItem(key: string): void { this.values.delete(key) }
}

/** Browser media boundary: playback changes time, commands may seek explicitly. */
class Media extends EventTarget {
  src = ''
  currentTime = 0
  duration = 10
  playbackRate = 1
  volume = 1
  ended = false
  paused = true
  seeking = false
  readyState = 4
  error: MediaError | null = null
  playImpl: () => Promise<void> = () => Promise.resolve()
  play(): Promise<void> { this.paused = false; return this.playImpl() }
  pause(): void { this.paused = true }
  load(): void { this.currentTime = 0; this.ended = false; this.error = null }
  removeAttribute(name: string): void { if (name === 'src') this.src = '' }
  emit(type: string): void {
    if (type === 'error') this.error = { code: 3 } as MediaError
    this.dispatchEvent(new Event(type))
  }
  finish(): void { this.currentTime = this.duration; this.ended = true; this.paused = true; this.emit('ended') }
}

const chapters: Chapter[] = [
  { title: 'Один', streamUrl: 'https://media.example/1.mp3', durationSeconds: 10 },
  { title: 'Два', streamUrl: 'https://media.example/2.mp3', durationSeconds: 10 },
  { title: 'Три', streamUrl: 'https://media.example/3.mp3', durationSeconds: 10 },
]
const detail = { title: 'Книга', editionId: 'edition', chapters }
const engines: AudioEngine[] = []
function fixture(): { player: AudioEngine; media: Media; store: LocalListeningStateStore } {
  const store = new LocalListeningStateStore(new MemoryStorage())
  const player = new AudioEngine({ store, relayBase: '/api', offlinePrimer: null })
  const media = new Media()
  player.attachAudio(media as unknown as HTMLAudioElement)
  engines.push(player)
  return { player, media, store }
}
beforeEach(() => { vi.useFakeTimers() })
afterEach(() => { engines.splice(0).forEach((e) => e.dispose()); vi.unstubAllGlobals(); vi.useRealTimers() })

describe('#934 — media owns position and natural chapter end', () => {
  it('buffering longer than metadata duration neither seeks media nor skips a chapter', async () => {
    const { player, media } = fixture()
    await player.loadBook(detail)
    media.emit('playing')
    media.currentTime = 2
    media.emit('timeupdate')
    media.emit('waiting')
    vi.advanceTimersByTime(35_000)
    expect(player.getState()).toMatchObject({ chapterIndex: 0, positionSeconds: 2, isCompleted: false })
    expect(media.currentTime).toBe(2)
    expect(media.src).toBe(chapters[0].streamUrl)
  })
  it('natural end changes chapter, source and saved place once; queued duplicate ended cannot skip', async () => {
    const { player, media, store } = fixture()
    await player.loadBook(detail)
    media.emit('playing')
    media.finish()
    media.emit('ended') // task from the resource that just ended, new resource has not ended
    vi.advanceTimersByTime(1_000)
    expect(player.getState()).toMatchObject({ chapterIndex: 1, positionSeconds: 0, isCompleted: false })
    expect(media.src).toBe(chapters[1].streamUrl)
    expect(store.load('edition')).toMatchObject({ chapterIndex: 1, positionSeconds: 0, isCompleted: false })
  })

  it('last completion parks at the actual end even when catalog duration is wrong', async () => {
    const { player, media, store } = fixture()
    await player.loadBook(detail, 2, { forceChapter: true })
    media.emit('playing')
    media.duration = 12.5
    media.finish()
    media.emit('ended')
    vi.advanceTimersByTime(5_000)
    expect(player.getState()).toMatchObject({ chapterIndex: 2, positionSeconds: 12.5, status: 'paused', isCompleted: true })
    expect(store.load('edition')).toMatchObject({ chapterIndex: 2, positionSeconds: 12.5, isCompleted: true })
  })

  it('pause saves the last actual media position even without a final timeupdate', async () => {
    const { player, media, store } = fixture()
    await player.loadBook(detail)
    media.emit('playing')
    media.currentTime = 7.25
    player.pause()
    expect(player.getState()).toMatchObject({ status: 'paused', positionSeconds: 7.25 })
    expect(store.load('edition')).toMatchObject({ positionSeconds: 7.25, isCompleted: false })
  })

  it('in-session Smart Rewind explicitly moves the media once and preserves the resumed position', async () => {
    const { player, media } = fixture()
    await player.loadBook({ ...detail, chapters: [{ ...chapters[0], durationSeconds: 100 }] })
    media.emit('playing')
    media.currentTime = 90
    player.pause()
    vi.advanceTimersByTime(3_600_000)
    player.play()
    expect(player.getState().positionSeconds).toBe(78)
    expect(media.currentTime).toBe(78)
    media.emit('timeupdate')
    player.pause()
    player.play()
    expect(media.currentTime).toBe(78)
    expect(player.getState().positionSeconds).toBe(78)
  })

  it('seek applies the same finite clamped media command and never completes a paused chapter', async () => {
    const { player, media } = fixture()
    await player.loadBook(detail)
    media.emit('playing')
    player.pause()
    player.seek(-500)
    expect(media.currentTime).toBe(0)
    expect(player.getState().positionSeconds).toBe(0)
    media.duration = 12.5 // actual media is longer than the source metadata
    player.seek(500)
    expect(media.currentTime).toBe(12.5)
    expect(player.getState().positionSeconds).toBe(12.5)
    player.seek(Number.NaN)
    expect(media.currentTime).toBe(12.5)
    media.emit('timeupdate')
    vi.advanceTimersByTime(5_000)
    expect(player.getState()).toMatchObject({ chapterIndex: 0, isCompleted: false, status: 'paused' })
  })

  it('sleep at chapter end waits for actual media through buffering and stops before next source', async () => {
    const { player, media, store } = fixture()
    await player.loadBook(detail)
    media.emit('playing')
    media.currentTime = 2
    media.emit('timeupdate')
    player.setSleepTimer(-1)
    vi.advanceTimersByTime(15_000)
    expect(player.getState()).toMatchObject({ status: 'playing', chapterIndex: 0, positionSeconds: 2 })
    expect(player.getSleepTimerState()).toMatchObject({ isEndOfChapter: true, remainingSeconds: 8 })
    media.finish()
    expect(player.getState()).toMatchObject({ status: 'paused', chapterIndex: 0, positionSeconds: 10, isCompleted: false })
    expect(media.src).toBe(chapters[0].streamUrl)
    expect(store.load('edition')).toMatchObject({ chapterIndex: 0, positionSeconds: 10 })
    expect(player.getSleepTimerState().minutes).toBe(0)
  })

  it('returning from background recovers actual ended flag when the event was never delivered', async () => {
    const page = new EventTarget()
    vi.stubGlobal('window', page)
    const { player, media, store } = fixture()
    await player.loadBook(detail)
    media.emit('playing')
    media.currentTime = 10
    media.ended = true
    media.paused = true
    // No ended event and no timer callback while browser suspended the page.
    page.dispatchEvent(new Event('pageshow'))
    expect(player.getState()).toMatchObject({ chapterIndex: 1, positionSeconds: 0 })
    expect(media.src).toBe(chapters[1].streamUrl)
    expect(store.load('edition')).toMatchObject({ chapterIndex: 1, positionSeconds: 0 })
    page.dispatchEvent(new Event('pageshow'))
    expect(player.getState().chapterIndex).toBe(1)
  })

  it('a resume waiting for cloud state cannot attribute the old resource events to the new edition', async () => {
    const { player, media, store } = fixture()
    await player.loadBook(detail)
    media.emit('playing')
    media.currentTime = 7
    media.emit('timeupdate')
    let answer!: () => void
    player.setSyncController({
      pullBeforeResume: () => new Promise<void>((resolve) => { answer = resolve }),
      pushAfterSave: () => Promise.resolve(),
    } as unknown as ProgressSyncController)
    const loading = player.loadBook({ ...detail, editionId: 'new-edition' })
    media.emit('timeupdate')
    media.finish()
    vi.advanceTimersByTime(35_000)
    expect(store.load('new-edition')).toBeNull()
    expect(media.src).toBe('')
    expect(store.load('edition')).toMatchObject({ chapterIndex: 0, positionSeconds: 7 })
    answer()
    await loading
    expect(player.getState()).toMatchObject({ editionId: 'new-edition', chapterIndex: 0, positionSeconds: 0 })
    expect(media.src).toBe(chapters[0].streamUrl)
  })

  it('an explicit seek to the end while paused remains paused when the page returns', async () => {
    const page = new EventTarget()
    vi.stubGlobal('window', page)
    const { player, media } = fixture()
    await player.loadBook(detail)
    media.emit('playing')
    player.pause()
    player.seek(10)
    media.ended = true // HTMLMediaElement.ended also reflects the end position after seek
    page.dispatchEvent(new Event('pageshow'))
    media.emit('timeupdate')
    expect(player.getState()).toMatchObject({ status: 'paused', chapterIndex: 0, positionSeconds: 10, isCompleted: false })
    expect(media.src).toBe(chapters[0].streamUrl)
  })

  it('timed sleep catches up delayed callbacks without inventing media progress', async () => {
    const { player, media } = fixture()
    await player.loadBook(detail)
    media.emit('playing')
    media.currentTime = 2
    media.emit('timeupdate')
    player.setSleepTimer(5)
    vi.setSystemTime(Date.now() + 30_000) // background suspended timer delivery
    vi.advanceTimersByTime(1_000)
    expect(player.getSleepTimerState().remainingSeconds).toBe(269)
    expect(player.getState()).toMatchObject({ positionSeconds: 2, chapterIndex: 0 })
  })

  it('relay fallback starts at the last actual position even without timeupdate', async () => {
    const { player, media } = fixture()
    await player.loadBook(detail)
    media.emit('playing')
    media.currentTime = 7
    media.emit('error')
    expect(player.getState()).toMatchObject({ chapterIndex: 0, positionSeconds: 7, attemptKind: 'relay' })
    expect(media.src).toBe('/api/audio?u=https%3A%2F%2Fmedia.example%2F1.mp3')
    expect(media.currentTime).toBe(7)
  })

  it('a delayed play rejection after user pause cannot restart audio through relay fallback', async () => {
    const { player, media } = fixture()
    await player.loadBook(detail)
    media.emit('playing')
    player.pause()
    let reject!: (reason: unknown) => void
    media.playImpl = () => new Promise<void>((_resolve, fail) => { reject = fail })
    player.play()
    player.pause()
    reject(new Error('late decode failure'))
    await Promise.resolve()
    await Promise.resolve()
    expect(player.getState()).toMatchObject({ status: 'paused', attemptKind: 'direct', chapterIndex: 0 })
    expect(media.src).toBe(chapters[0].streamUrl)
    expect(media.paused).toBe(true)
  })

  it('speed commands preserve position, reject invalid rates, and end sleep reflects actual rate', async () => {
    const { player, media } = fixture()
    await player.loadBook(detail)
    media.emit('playing')
    media.currentTime = 2
    player.setSpeed(2)
    player.setSleepTimer(-1)
    vi.advanceTimersByTime(1_000)
    expect(player.getState()).toMatchObject({ speed: 2, positionSeconds: 2 })
    expect(media.playbackRate).toBe(2)
    expect(player.getSleepTimerState().remainingSeconds).toBe(4)
    player.setSpeed(Number.NaN)
    player.setSpeed(-1)
    expect(player.getState().speed).toBe(2)
    expect(media.playbackRate).toBe(2)
  })

  it('resume and a newer explicit seek wait for metadata without losing their commanded position', async () => {
    const { player, media, store } = fixture()
    store.save({ editionId: 'edition', chapterIndex: 0, positionSeconds: 8, isCompleted: false, preferredSpeed: 1.25, lastPausedAtEpochMs: null })
    let position = 0
    Object.defineProperty(media, 'currentTime', {
      get: () => position,
      set: (value: number) => {
        if (media.readyState === 0) throw new DOMException('No metadata', 'InvalidStateError')
        position = value
      },
    })
    media.load = () => { position = 0; media.ended = false; media.readyState = 0 }
    await expect(player.loadBook(detail)).resolves.toBe(true)
    expect(player.getState().positionSeconds).toBe(8)
    player.seek(3)
    vi.advanceTimersByTime(5_000)
    expect(player.getState().positionSeconds).toBe(3)
    media.readyState = 4
    media.emit('loadedmetadata')
    media.emit('playing')
    expect(media.currentTime).toBe(3)
    expect(player.getState()).toMatchObject({ positionSeconds: 3, speed: 1.25 })
  })

  it('a browser media pause records actual position instead of leaving a playing engine', async () => {
    const { player, media, store } = fixture()
    await player.loadBook(detail)
    media.emit('playing')
    media.currentTime = 4.5
    media.pause()
    media.emit('pause')
    expect(player.getState()).toMatchObject({ status: 'paused', positionSeconds: 4.5, chapterIndex: 0 })
    expect(store.load('edition')).toMatchObject({ positionSeconds: 4.5, isCompleted: false })
  })

  it('Play after a completed edition starts its first chapter from zero', async () => {
    const { player, media } = fixture()
    await player.loadBook(detail, 2, { forceChapter: true })
    media.emit('playing')
    media.finish()
    expect(player.getState().isCompleted).toBe(true)
    player.play()
    expect(player.getState()).toMatchObject({ status: 'playing', chapterIndex: 0, positionSeconds: 0, isCompleted: false })
    expect(media.src).toBe(chapters[0].streamUrl)
    expect(media.currentTime).toBe(0)
  })

  it('replacement media binding owns the current source and position; old element events are inert', async () => {
    const { player, media } = fixture()
    await player.loadBook(detail)
    media.emit('playing')
    media.currentTime = 7
    const replacement = new Media()
    player.attachAudio(replacement as unknown as HTMLAudioElement)
    expect(replacement.src).toBe(chapters[0].streamUrl)
    expect(replacement.currentTime).toBe(7)
    expect(media.paused).toBe(true)
    media.finish()
    expect(player.getState()).toMatchObject({ chapterIndex: 0, positionSeconds: 7 })
    replacement.emit('playing')
    replacement.finish()
    expect(player.getState().chapterIndex).toBe(1)
    expect(replacement.src).toBe(chapters[1].streamUrl)
  })

  it('polling the ended flag without its event advances once; the later event is stale', async () => {
    const { player, media } = fixture()
    await player.loadBook(detail)
    media.emit('playing')
    media.currentTime = 10
    media.ended = true
    media.paused = true
    vi.advanceTimersByTime(1_000)
    media.emit('ended')
    expect(player.getState()).toMatchObject({ chapterIndex: 1, positionSeconds: 0 })
    expect(media.src).toBe(chapters[1].streamUrl)
  })

  it('media pause at natural end and its later ended task share one transition', async () => {
    const { player, media } = fixture()
    await player.loadBook(detail)
    media.emit('playing')
    media.currentTime = 10
    media.ended = true
    media.paused = true
    media.emit('pause')
    media.emit('ended')
    expect(player.getState()).toMatchObject({ chapterIndex: 1, positionSeconds: 0 })
    expect(media.src).toBe(chapters[1].streamUrl)
  })

  it('an old timeupdate callback cannot overwrite a newer chapter after a manual jump', async () => {
    const { player, media } = fixture()
    const callbacks: EventListenerOrEventListenerObject[] = []
    const add = media.addEventListener.bind(media)
    media.addEventListener = (type, listener, options) => {
      if (type === 'timeupdate' && listener) callbacks.push(listener)
      add(type, listener, options)
    }
    await player.loadBook(detail)
    media.emit('playing')
    const oldUpdate = callbacks[0]
    player.nextChapter()
    media.currentTime = 8
    if (typeof oldUpdate === 'function') oldUpdate.call(media, new Event('timeupdate'))
    expect(player.getState()).toMatchObject({ chapterIndex: 1, positionSeconds: 0 })
    expect(media.src).toBe(chapters[1].streamUrl)
  })

  it('unknown catalog duration completes honestly from the actual last media end', async () => {
    const { player, media, store } = fixture()
    await player.loadBook({ ...detail, chapters: [{ title: 'Невідомий час', streamUrl: chapters[0].streamUrl }] })
    media.emit('playing')
    media.duration = 6.75
    media.finish()
    expect(player.getState()).toMatchObject({ status: 'paused', positionSeconds: 6.75, isCompleted: true })
    expect(store.load('edition')).toMatchObject({ positionSeconds: 6.75, isCompleted: true })
  })

  it('user pause of an unplayed prepare preserves the last confirmed Listening State', async () => {
    const { player, media, store } = fixture()
    store.save({ editionId: 'edition', chapterIndex: 1, positionSeconds: 7, isCompleted: false, preferredSpeed: 1, lastPausedAtEpochMs: null })
    media.playImpl = () => new Promise<void>(() => {})
    await player.loadBook(detail, 0, { forceChapter: true })
    player.pause()
    expect(player.getState().status).toBe('paused')
    expect(store.load('edition')).toMatchObject({ chapterIndex: 1, positionSeconds: 7 })
  })

  it('superseding an unplayed prepare does not turn its unconfirmed zero into a rollback baseline', async () => {
    const { player, media, store } = fixture()
    store.save({ editionId: 'edition', chapterIndex: 1, positionSeconds: 7, isCompleted: false, preferredSpeed: 1, lastPausedAtEpochMs: null })
    media.playImpl = () => new Promise<void>(() => {})
    await player.loadBook(detail, 0, { forceChapter: true })
    await player.loadBook(detail, 2, { forceChapter: true })
    media.emit('error')
    media.emit('error')
    expect(player.getState().status).toBe('unavailable')
    expect(store.load('edition')).toMatchObject({ chapterIndex: 1, positionSeconds: 7 })
  })

})
