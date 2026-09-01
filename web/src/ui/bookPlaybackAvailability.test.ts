import { describe, expect, it } from 'vitest'
import { canPlayBookFromDisplayedDetail, sourceNeedsBrowserSession } from './bookPlaybackAvailability'

describe('cached book playback availability', () => {
  it('does not make a session-only source playable from saved metadata', () => {
    expect(sourceNeedsBrowserSession('fourread')).toBe(true)
    expect(canPlayBookFromDisplayedDetail('fourread', true)).toBe(false)
  })

  it('keeps a fresh verified page and non-session sources playable', () => {
    expect(canPlayBookFromDisplayedDetail('fourread', false)).toBe(true)
    expect(canPlayBookFromDisplayedDetail('sluhay', true)).toBe(true)
  })
})
