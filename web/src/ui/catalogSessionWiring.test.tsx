// @vitest-environment jsdom
/**
 * #621 — the React wiring of the ONE Catalog session: the component keeps its
 * external states (loading / ready / cached / failed / searching), while the
 * session owns the generation, the abort and the cache write. These tests use
 * the real `api` + warm cache seams and controlled promises.
 */
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import 'fake-indexeddb/auto'
import { IDBFactory } from 'fake-indexeddb'
import { api } from '../api/client'
import { readWarmEntry, warmKey } from '../api/warmCache'
import { setUiLocale } from '../i18n/locale'
import { Catalog } from './Catalog'
import { resetSearchMemory } from './searchMemory'
import type { SourceId, UnifiedWork, UnifiedWorkPage } from '../worker/types'

function work(id: string, title: string, language?: string, sourceId: SourceId = 'sluhay'): UnifiedWork {
  return {
    id,
    mergeKey: id,
    title,
    author: 'Автор',
    editions: [{ id: `${id}-e`, language, narrator: 'Читець', sources: [{ sourceId, url: `https://sluhay.com/${id}` }] }],
  }
}

beforeEach(() => {
  globalThis.indexedDB = new IDBFactory()
  setUiLocale('uk')
  resetSearchMemory()
  vi.spyOn(api, 'catalog').mockResolvedValue({ sections: [] })
})

afterEach(() => {
  cleanup()
  vi.useRealTimers()
  vi.restoreAllMocks()
  window.localStorage.clear()
})

describe('#621 Catalog session wiring', () => {
  it('switching Source aborts the pending request and drops its late page', async () => {
    const pageAll: UnifiedWorkPage = { works: [work('all-1', 'Перша')], nextCursor: 'cursor-a2' }
    const pageSluhay: UnifiedWorkPage = { works: [work('s-1', 'Друга')] }
    let releaseLate: (page: UnifiedWorkPage) => void = () => {}
    const late = new Promise<UnifiedWorkPage>((resolve) => { releaseLate = resolve })
    const signals: Array<{ cursor?: string; source?: SourceId; signal?: AbortSignal }> = []
    vi.spyOn(api, 'workFeed').mockImplementation((cursor?: string, source?: SourceId, signal?: AbortSignal) => {
      signals.push({ cursor, source, signal })
      if (cursor === 'cursor-a2') return late
      if (source === 'sluhay') return Promise.resolve(pageSluhay)
      return Promise.resolve(pageAll)
    })

    const user = userEvent.setup()
    render(<Catalog onOpenBook={vi.fn()} onPlay={vi.fn(async () => true)} />)

    await waitFor(() => expect(screen.getAllByText('Перша').length).toBeGreaterThan(0))
    await user.click(screen.getByRole('button', { name: 'Показати більше' }))
    // Source changes while the A page is still in flight → cancelled.
    await user.click(screen.getByRole('button', { name: 'Sluhay' }))
    await waitFor(() => expect(screen.getAllByText('Друга').length).toBeGreaterThan(0))
    expect(signals.find((call) => call.cursor === 'cursor-a2')?.signal?.aborted).toBe(true)

    releaseLate({ works: [work('all-2', 'Третя')] })
    await act(async () => { await Promise.resolve() })

    // Neither the screen nor the previous Source's cache sees the late page.
    expect(screen.queryByText('Третя')).toBeNull()
    const cached = await readWarmEntry<UnifiedWorkPage>(warmKey('catalog', 'all'))
    expect(cached?.value.works.map((item) => item.id) ?? []).not.toContain('s-1')
    expect(cached?.value.works.map((item) => item.id) ?? []).not.toContain('all-2')
  })

  it('a hung feed shows the honest failure after the 15 s budget', async () => {
    vi.useFakeTimers()
    vi.spyOn(api, 'workFeed').mockImplementation(() => new Promise(() => {}))

    render(<Catalog onOpenBook={vi.fn()} onPlay={vi.fn(async () => true)} />)
    await act(async () => { await Promise.resolve() })
    await act(async () => { await vi.advanceTimersByTimeAsync(15_001) })

    expect(screen.queryByText(/Завантажуємо/)).toBeNull()
    expect(screen.getByText('Джерело не відповіло спробуйте пізніше.')).toBeTruthy()
    vi.useRealTimers()
  })

  it('an empty successful search is not a failed search', async () => {
    vi.spyOn(api, 'workFeed').mockResolvedValue({ works: [work('a', 'Книга', 'uk')] })
    const search = vi.spyOn(api, 'workSearch').mockResolvedValue({ works: [] })
    const user = userEvent.setup()
    render(<Catalog onOpenBook={vi.fn()} onPlay={vi.fn(async () => true)} />)

    await user.click(screen.getByRole('button', { name: 'Пошук' }))
    await user.type(screen.getByRole('searchbox'), 'море')
    await waitFor(() => expect(screen.getByText('Нічого не знайшли.')).toBeTruthy())
    expect(screen.queryByText('Джерело не відповіло спробуйте пізніше.')).toBeNull()

    search.mockResolvedValue(null)
    await user.clear(screen.getByRole('searchbox'))
    await user.type(screen.getByRole('searchbox'), 'тиша')
    await waitFor(() => expect(screen.getByText('Джерело не відповіло спробуйте пізніше.')).toBeTruthy())
    expect(screen.queryByText('Нічого не знайшли.')).toBeNull()
  })

  it('the Content Language Preference never refetches and keeps unknown Editions visible', async () => {
    window.localStorage.setItem('slukhayka.content_languages', JSON.stringify(['uk']))
    const unknown = work('unknown-lang', 'Без мови')
    unknown.editions[0]!.language = undefined
    const feedSpy = vi.spyOn(api, 'workFeed').mockResolvedValue({ works: [unknown] })

    render(<Catalog onOpenBook={vi.fn()} onPlay={vi.fn(async () => true)} />)
    await waitFor(() => expect(screen.getAllByText('Без мови').length).toBeGreaterThan(0))
    expect(feedSpy).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('button', { name: 'Українська' }).getAttribute('aria-pressed')).toBe('true')
  })
})

/** #624 — a controllable IntersectionObserver: the test decides when it fires. */
class FakeIntersectionObserver {
  static all: FakeIntersectionObserver[] = []
  connected = false
  root: Element | null = null
  rootMargin = ''
  thresholds: number[] = []
  constructor(public callback: IntersectionObserverCallback) { FakeIntersectionObserver.all.push(this) }
  observe(): void { this.connected = true }
  unobserve(): void {}
  disconnect(): void { this.connected = false }
  takeRecords(): IntersectionObserverEntry[] { return [] }
  fire(): void {
    this.callback([{ isIntersecting: true } as IntersectionObserverEntry], this as unknown as IntersectionObserver)
  }
}

describe('#624 observer and manual pagination share the session', () => {
  beforeEach(() => {
    FakeIntersectionObserver.all = []
    ;(globalThis as { IntersectionObserver?: unknown }).IntersectionObserver = FakeIntersectionObserver
  })

  afterEach(() => {
    delete (globalThis as { IntersectionObserver?: unknown }).IntersectionObserver
  })

  it('runs one request for observer + manual, stops on a repeated cursor and retries explicitly', async () => {
    const cursors: Array<string | undefined> = []
    let secondPage: UnifiedWorkPage | null = { works: [work('all-2', 'Друга')], nextCursor: 'cursor-2' }
    vi.spyOn(api, 'workFeed').mockImplementation((cursor, source): Promise<UnifiedWorkPage | null> => {
      cursors.push(cursor)
      if (source === 'sluhay') return Promise.resolve({ works: [work('s-1', 'Друга з Sluhay', undefined, 'sluhay')] })
      if (cursor === undefined) return Promise.resolve({ works: [work('all-1', 'Перша')], nextCursor: 'cursor-2' })
      return Promise.resolve(secondPage)
    })

    render(<Catalog onOpenBook={vi.fn()} onPlay={vi.fn(async () => true)} />)
    await waitFor(() => expect(screen.getAllByText('Перша').length).toBeGreaterThan(0))

    // The observer fires and the listener presses «Показати більше» for the SAME
    // cursor in one tick: one request, no second append.
    await act(async () => {
      FakeIntersectionObserver.all.find((observer) => observer.connected)?.fire()
      fireEvent.click(screen.getByRole('button', { name: 'Показати більше' }))
      await Promise.resolve()
    })

    // The source answered with the cursor it was asked for: auto-loading stops
    // and the explicit retry replaces the loop.
    await waitFor(() => expect(screen.getByRole('button', { name: 'Спробувати ще раз' })).toBeTruthy())
    expect(cursors.filter((cursor) => cursor === 'cursor-2')).toHaveLength(1)
    expect(FakeIntersectionObserver.all.some((observer) => observer.connected)).toBe(false)
    expect(screen.getAllByText('Перша').length).toBeGreaterThan(0)

    // The explicit retry asks for the same page; this time the source advances.
    secondPage = { works: [work('all-3', 'Третя')], nextCursor: 'cursor-3' }
    fireEvent.click(screen.getByRole('button', { name: 'Спробувати ще раз' }))
    await waitFor(() => expect(screen.getAllByText('Третя').length).toBeGreaterThan(0))
    expect(cursors.filter((cursor) => cursor === 'cursor-2')).toHaveLength(2)
    expect(screen.getAllByText('Перша').length).toBeGreaterThan(0)

    // Switching Source drops the previous Source's Works.
    fireEvent.click(screen.getByRole('button', { name: 'Sluhay' }))
    await waitFor(() => expect(screen.getAllByText('Друга з Sluhay').length).toBeGreaterThan(0))
    expect(screen.queryByText('Третя')).toBeNull()
  })
})
