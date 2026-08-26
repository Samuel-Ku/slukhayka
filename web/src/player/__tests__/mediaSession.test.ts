import { describe, expect, it } from 'vitest'
import { updateNowPlaying, type MediaSessionHandlers } from '../mediaSession'

/**
 * spec-43/T5 — smoke tests over a fake globalThis.navigator: the supported
 * path wires metadata + the four action handlers to the callbacks; a
 * navigator without mediaSession is a silent no-op.
 */

interface CapturedSession {
  metadata: Record<string, string> | null
  handlers: Partial<Record<MediaSessionActionSource, () => void>>
}

type MediaSessionActionSource = 'play' | 'pause' | 'previoustrack' | 'nexttrack'

function installFakeNavigator(supported: boolean): CapturedSession | null {
  const captured: CapturedSession = { metadata: null, handlers: {} }
  const nav = supported
    ? {
        mediaSession: {
          get metadata(): unknown {
            return captured.metadata
          },
          set metadata(value: unknown) {
            captured.metadata = value as Record<string, string>
          },
          setActionHandler(action: string, handler: (() => void) | null): void {
            captured.handlers[action as MediaSessionActionSource] = handler ?? undefined
          },
        },
      }
    : {}
  Object.defineProperty(globalThis, 'navigator', {
    value: nav,
    configurable: true,
    writable: true,
  })
  return supported ? captured : null
}

const handlers: MediaSessionHandlers = {
  onPlay: () => {},
  onPause: () => {},
  onPreviousTrack: () => {},
  onNextTrack: () => {},
}

describe('updateNowPlaying', () => {
  it('wires the four action handlers and metadata when mediaSession exists', () => {
    const captured = installFakeNavigator(true)
    if (captured === null) throw new Error('fake navigator missing')
    const calls: string[] = []
    updateNowPlaying(
      { title: 'Тіні забутих предків', author: 'Михайло Коцюбинський', chapterTitle: 'Розділ 1' },
      {
        onPlay: () => calls.push('play'),
        onPause: () => calls.push('pause'),
        onPreviousTrack: () => calls.push('prev'),
        onNextTrack: () => calls.push('next'),
      }
    )
    expect(captured.handlers.play).toBeTypeOf('function')
    expect(captured.handlers.pause).toBeTypeOf('function')
    expect(captured.handlers.previoustrack).toBeTypeOf('function')
    expect(captured.handlers.nexttrack).toBeTypeOf('function')
    captured.handlers.play?.()
    expect(calls).toEqual(['play'])
  })

  it('is a no-op without mediaSession support', () => {
    installFakeNavigator(false)
    expect(() =>
      updateNowPlaying({ title: 'x', author: 'y' }, handlers)
    ).not.toThrow()
    restoreNavigator()
  })
})

function restoreNavigator(): void {
  Object.defineProperty(globalThis, 'navigator', {
    value: undefined,
    configurable: true,
    writable: true,
  })
}
