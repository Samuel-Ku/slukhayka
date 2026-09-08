import { describe, expect, it } from 'vitest'
import { AudioEngine, relayUrlFor } from '../audioEngine'
import type { OfflineAudioPrimer } from '../../offline/primer'

class FakeAudio extends EventTarget {
  src = ''
  currentTime = 0
  playbackRate = 1
  play(): Promise<void> { return Promise.resolve() }
  pause(): void {}
}

/** A recording primer stub — the offline-cache behaviour itself is tested in offline/primer.test.ts. */
function recordingPrimer(): OfflineAudioPrimer & { primed: string[] } {
  const primed: string[] = []
  return {
    primed,
    prime: (url: string) => {
      primed.push(url)
      return Promise.resolve()
    },
    isCached: () => Promise.resolve(false),
    cachedStreamUrls: () => Promise.resolve(new Set()),
  } as unknown as OfflineAudioPrimer & { primed: string[] }
}

describe('AudioEngine catalogue confirmation', () => {
  it('does not confirm playback until the audio element emits playing', async () => {
    const engine = new AudioEngine()
    const audio = new FakeAudio()
    engine.attachAudio(audio as unknown as HTMLAudioElement)
    const result = engine.loadBookAndAwaitPlaying({
      title: 'Книга',
      chapters: [{ title: 'Розділ', streamUrl: 'https://audio.example/1.mp3' }],
    }, 0, 100)

    let settled = false
    void result.then(() => { settled = true })
    await Promise.resolve()
    expect(settled).toBe(false)

    audio.dispatchEvent(new Event('playing'))
    await expect(result).resolves.toBe(true)
  })

  it('keeps a failed playback attempt distinct from a playable book response', async () => {
    const engine = new AudioEngine()
    const audio = new FakeAudio()
    engine.attachAudio(audio as unknown as HTMLAudioElement)
    const result = engine.loadBookAndAwaitPlaying({
      title: 'Книга',
      chapters: [{ title: 'Розділ', streamUrl: 'https://audio.example/1.mp3' }],
    }, 0, 100)

    audio.dispatchEvent(new Event('error'))
    await expect(result).resolves.toBe(false)
  })

  it('never constructs a Worker relay URL from a query-bearing stream locator', () => {
    expect(relayUrlFor('/api', 'https://audio.example/chapter.mp3?X-Amz-Signature=private')).toBe('')
    expect(relayUrlFor('/api', 'https://audio.example/chapter.mp3')).toBe('/api/audio?u=https%3A%2F%2Faudio.example%2Fchapter.mp3')
  })

  it('primes the playing chapter`s relay URL for the offline cache (W6.2)', async () => {
    const primer = recordingPrimer()
    const engine = new AudioEngine({ relayBase: '/api', offlinePrimer: primer })
    const audio = new FakeAudio()
    engine.attachAudio(audio as unknown as HTMLAudioElement)
    const result = engine.loadBookAndAwaitPlaying({
      title: 'Книга',
      chapters: [{ title: 'Розділ', streamUrl: 'https://audio.example/1.mp3' }],
    }, 0, 100)
    audio.dispatchEvent(new Event('playing'))
    await expect(result).resolves.toBe(true)
    expect(primer.primed).toEqual(['/api/audio?u=https%3A%2F%2Faudio.example%2F1.mp3'])
  })

  it('skips priming without a relay (no same-origin path to cache)', async () => {
    const primer = recordingPrimer()
    const engine = new AudioEngine({ offlinePrimer: primer })
    const audio = new FakeAudio()
    engine.attachAudio(audio as unknown as HTMLAudioElement)
    const result = engine.loadBookAndAwaitPlaying({
      title: 'Книга',
      chapters: [{ title: 'Розділ', streamUrl: 'https://audio.example/1.mp3' }],
    }, 0, 100)
    audio.dispatchEvent(new Event('playing'))
    await expect(result).resolves.toBe(true)
    expect(primer.primed).toEqual([])
  })

  it('does not prime query-bearing stream locators (no honest relay target)', async () => {
    const primer = recordingPrimer()
    const engine = new AudioEngine({ relayBase: '/api', offlinePrimer: primer })
    const audio = new FakeAudio()
    engine.attachAudio(audio as unknown as HTMLAudioElement)
    const result = engine.loadBookAndAwaitPlaying({
      title: 'Книга',
      chapters: [{ title: 'Розділ', streamUrl: 'https://audio.example/1.mp3?signed=1' }],
    }, 0, 100)
    audio.dispatchEvent(new Event('playing'))
    await expect(result).resolves.toBe(true)
    expect(primer.primed).toEqual([])
  })
})
