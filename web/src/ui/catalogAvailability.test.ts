import { describe, expect, it, vi } from 'vitest'
import {
  AVAILABILITY_POLICY,
  isAvailabilityFresh,
  preflightMediaRange,
  probeStreamPlaying,
  raceEditionSources,
  type AvailabilityVerdict,
} from './catalogAvailability'

describe('catalog availability policy', () => {
  it('expires positive, negative and verified-profile assertions at the exact boundary', () => {
    const observedAt = 10_000
    expect(isAvailabilityFresh('playing', observedAt, observedAt + AVAILABILITY_POLICY.positiveTtlMs - 1)).toBe(true)
    expect(isAvailabilityFresh('playing', observedAt, observedAt + AVAILABILITY_POLICY.positiveTtlMs)).toBe(false)
    expect(isAvailabilityFresh('audio-missing', observedAt, observedAt + AVAILABILITY_POLICY.negativeTtlMs - 1)).toBe(true)
    expect(isAvailabilityFresh('audio-missing', observedAt, observedAt + AVAILABILITY_POLICY.negativeTtlMs)).toBe(false)
    expect(isAvailabilityFresh('verified-profile', observedAt, observedAt + AVAILABILITY_POLICY.verifiedProfileTtlMs - 1)).toBe(true)
    expect(isAvailabilityFresh('verified-profile', observedAt, observedAt + AVAILABILITY_POLICY.verifiedProfileTtlMs)).toBe(false)
  })

  it('runs at most two sources from the selected Edition and cancels the loser after playing', async () => {
    const attempts: string[] = []
    const cancelled: string[] = []
    const result = raceEditionSources(
      'edition-a',
      [
        { editionId: 'edition-a', sourceId: 'sound-books', url: 'https://a/1' },
        { editionId: 'edition-a', sourceId: 'sluhayua', url: 'https://a/2' },
        { editionId: 'edition-a', sourceId: 'lihtar', url: 'https://a/3' },
        { editionId: 'edition-b', sourceId: 'fourread', url: 'https://b/1' },
      ],
      async (candidate, signal) => {
        attempts.push(candidate.url)
        signal.addEventListener('abort', () => cancelled.push(candidate.url))
        if (candidate.url.endsWith('/2')) return 'playing'
        await new Promise<void>((resolve) => signal.addEventListener('abort', () => resolve()))
        return 'temporary-failure'
      },
    )

    await expect(result).resolves.toMatchObject({ verdict: 'playing', candidate: { url: 'https://a/2' } })
    expect(attempts).toEqual(['https://a/1', 'https://a/2'])
    expect(cancelled).toContain('https://a/1')
  })

  it('returns a stable terminal category when every compatible source fails', async () => {
    vi.useFakeTimers()
    const terminal: Array<Exclude<AvailabilityVerdict, 'playing' | 'verified-profile'>> = ['audio-missing', 'session-required']
    const promise = raceEditionSources(
      'edition-a',
      terminal.map((_, index) => ({ editionId: 'edition-a', sourceId: 'fourread' as const, url: `https://a/${index}` })),
      async (candidate) => terminal[Number(candidate.url.at(-1))],
    )
    await expect(promise).resolves.toMatchObject({ verdict: 'session-required' })
    vi.useRealTimers()
  })

  it('cancels an active race without accepting its late result', async () => {
    const action = new AbortController()
    let release: (() => void) | undefined
    const result = raceEditionSources(
      'edition-a',
      [{ editionId: 'edition-a', sourceId: 'sound-books', url: 'https://a/1' }],
      async (_candidate, signal) => {
        await new Promise<void>((resolve) => {
          release = resolve
          signal.addEventListener('abort', () => resolve(), { once: true })
        })
        return signal.aborted ? 'timeout' : 'playing'
      },
      8_000,
      action.signal,
    )
    action.abort()
    release?.()
    await expect(result).resolves.toMatchObject({ verdict: 'timeout' })
  })

  it('rejects HTML and accepts a bounded relay media response', async () => {
    const calls: string[] = []
    const fetcher: typeof fetch = vi.fn(async (input) => {
      const url = String(input)
      calls.push(url)
      if (calls.length === 1) {
        return new Response('<html>challenge</html>', { status: 200, headers: { 'content-type': 'text/html' } })
      }
      return new Response(new Uint8Array([1, 2, 3]), { status: 206, headers: { 'content-type': 'audio/mpeg' } })
    }) as typeof fetch

    await expect(preflightMediaRange('https://audio.example/a.mp3', new AbortController().signal, undefined, fetcher)).resolves.toBe(true)
    expect(calls).toHaveLength(2)
  })

  it('uses the Worker relay only after the direct audio element fails to play', async () => {
    const attempts: string[] = []
    class DirectThenRelayAudio extends EventTarget {
      muted = false
      preload = ''
      private value = ''
      set src(value: string) { this.value = value }
      get src(): string { return this.value }
      play = vi.fn(async () => {
        attempts.push(this.value)
        queueMicrotask(() => this.dispatchEvent(new Event(this.value.includes('/api/audio') ? 'playing' : 'error')))
      })
      pause = vi.fn()
      load = vi.fn()
      removeAttribute = vi.fn()
    }
    vi.stubGlobal('Audio', DirectThenRelayAudio)

    await expect(probeStreamPlaying('https://audio.example/book.mp3', new AbortController().signal)).resolves.toBe(true)
    expect(attempts).toEqual([
      'https://audio.example/book.mp3',
      '/api/audio?u=https%3A%2F%2Faudio.example%2Fbook.mp3',
    ])
  })

  it('never sends a signed stream URL to the Worker relay', async () => {
    const attempts: string[] = []
    class FailingAudio extends EventTarget {
      muted = false
      preload = ''
      private value = ''
      set src(value: string) { this.value = value }
      get src(): string { return this.value }
      play = vi.fn(async () => {
        attempts.push(this.value)
        queueMicrotask(() => this.dispatchEvent(new Event('error')))
      })
      pause = vi.fn()
      load = vi.fn()
      removeAttribute = vi.fn()
    }
    vi.stubGlobal('Audio', FailingAudio)

    await expect(probeStreamPlaying('https://audio.example/book.mp3?token=private', new AbortController().signal)).resolves.toBe(false)
    expect(attempts).toEqual(['https://audio.example/book.mp3?token=private'])
  })
})
