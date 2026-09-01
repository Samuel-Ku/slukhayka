import { describe, expect, it } from 'vitest'
import { AudioEngine } from '../audioEngine'

class FakeAudio extends EventTarget {
  src = ''
  currentTime = 0
  playbackRate = 1
  play(): Promise<void> { return Promise.resolve() }
  pause(): void {}
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
})
