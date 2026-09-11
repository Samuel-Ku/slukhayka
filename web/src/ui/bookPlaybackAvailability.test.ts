import { describe, expect, it } from 'vitest'
import { canPlayBookFromDisplayedDetail, sourceNeedsBrowserSession } from './bookPlaybackAvailability'
import { publicBookProjection } from './BookPage'

describe('cached book playback availability', () => {
  it('does not make a session-only source playable from saved metadata', () => {
    expect(sourceNeedsBrowserSession('ukrainianaudiobooks')).toBe(true)
    expect(canPlayBookFromDisplayedDetail('ukrainianaudiobooks', true)).toBe(false)
  })

  it('never treats a scam source as playable or a session door', () => {
    // 4read's audio is not the book: no browser door, no play — cached or not.
    expect(sourceNeedsBrowserSession('fourread')).toBe(false)
    expect(canPlayBookFromDisplayedDetail('fourread', true)).toBe(false)
    expect(canPlayBookFromDisplayedDetail('fourread', false)).toBe(false)
  })

  it('keeps a fresh verified page and non-session sources playable', () => {
    expect(canPlayBookFromDisplayedDetail('ukrainianaudiobooks', false)).toBe(true)
    expect(canPlayBookFromDisplayedDetail('sluhay', true)).toBe(true)
  })

  it('keeps only public direct stream locators in the offline book projection', () => {
    const projected = publicBookProjection({
      url: 'https://source.example/book', title: 'Книга', author: 'Автор', genres: [], otherNarrations: [], relatedBooks: [],
      chapters: [
        { title: 'Public', streamUrl: 'https://audio.example/public.mp3' },
        { title: 'Signed', streamUrl: 'https://audio.example/signed.mp3?X-Amz-Signature=private' },
      ],
    })
    expect(projected.chapters.map((chapter) => chapter.title)).toEqual(['Public'])
    expect(JSON.stringify(projected)).not.toContain('private')
  })
})
