import { describe, expect, it } from 'vitest'
import { canPlayBookFromDisplayedDetail, sourceNeedsBrowserSession } from './bookPlaybackAvailability'
import { publicBookProjection } from './BookPage'

describe('cached book playback availability', () => {
  it('does not make a session-only source playable from saved metadata', () => {
    expect(sourceNeedsBrowserSession('fourread')).toBe(true)
    expect(canPlayBookFromDisplayedDetail('fourread', true)).toBe(false)
  })

  it('keeps a fresh verified page and non-session sources playable', () => {
    expect(canPlayBookFromDisplayedDetail('fourread', false)).toBe(true)
    expect(canPlayBookFromDisplayedDetail('sluhay', true)).toBe(true)
  })

  it('keeps only public direct stream locators in the offline book projection', () => {
    const projected = publicBookProjection({
      url: 'https://source.example/book', title: 'Книга', author: 'Автор', genres: [], otherNarrations: [],
      chapters: [
        { title: 'Public', streamUrl: 'https://audio.example/public.mp3' },
        { title: 'Signed', streamUrl: 'https://audio.example/signed.mp3?token=private' },
      ],
    })
    expect(projected.chapters.map((chapter) => chapter.title)).toEqual(['Public'])
    expect(JSON.stringify(projected)).not.toContain('private')
  })
})
