import { afterEach, describe, expect, it, vi } from 'vitest'
import { api, dedupeWorks } from './client'
import { cardResultState } from '../ui/Catalog'

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('dedupeWorks', () => {
  it('keeps distinct Source editions of the same Work', () => {
    const groups = dedupeWorks([
      { id: 'fourread', displayName: '4read', cards: [{ url: 'https://4read.org/a', title: 'Книга', author: 'Автор' }] },
      { id: 'sluhay', displayName: 'Sluhay', cards: [{ url: 'https://sluhay.com/a', title: 'Книга', author: 'Автор' }] },
    ])

    expect(groups).toHaveLength(2)
    expect(groups.flatMap((group) => group.cards)).toHaveLength(2)
  })
})

describe('card action terminal states', () => {
  const playable = { chapters: [{ title: '1', streamUrl: 'https://example.test/1.mp3' }] } as never
  const missing = { chapters: [] } as never

  it('does not treat an HTTP-shaped response without audio as playable', () => {
    expect(cardResultState(playable, false, true)).toBe('ready')
    expect(cardResultState(missing, false, true)).toBe('audio-missing')
  })

  it('keeps no-network, temporary failure and session-required distinct', () => {
    expect(cardResultState(null, false, false)).toBe('no-network')
    expect(cardResultState(null, false, true)).toBe('temporary-failure')
    expect(cardResultState(missing, true, true)).toBe('browser-required')
  })
})

describe('#621 cancellable work feed and search', () => {
  it('forwards the AbortSignal to the transport', async () => {
    const fetcher = vi.fn((_input: RequestInfo | URL, _init?: RequestInit) =>
      Promise.resolve(new Response(JSON.stringify({ ok: true, data: { works: [] } }), { status: 200 })),
    )
    vi.stubGlobal('fetch', fetcher)
    const controller = new AbortController()

    await api.workFeed(undefined, undefined, controller.signal)
    expect((fetcher.mock.calls[0]?.[1] as RequestInit | undefined)?.signal).toBe(controller.signal)

    await api.workSearch('море', 'sluhay', controller.signal)
    expect((fetcher.mock.calls[1]?.[1] as RequestInit | undefined)?.signal).toBe(controller.signal)
  })
})
