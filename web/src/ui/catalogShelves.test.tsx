// @vitest-environment jsdom
/**
 * W3.1 — the Огляд shelves: the #302 pin order (search → … → «Відкрити нове»
 * → feed), the cross-source «Новинки» rail with source badges, «Цикли» via
 * the worker, «Колекції» matched locally from the shipped assets, the
 * snapshot TTL (offline start from the last snapshot with an honest note)
 * and the explicit refresh.
 */
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import 'fake-indexeddb/auto'
import { IDBFactory } from 'fake-indexeddb'
import { api } from '../api/client'
import { warmKey, writeWarm } from '../api/warmCache'
import { setUiLocale } from '../i18n/locale'
import { NEW_ARRIVALS_TTL_MS } from './feedSnapshotPolicy'
import { Catalog } from './Catalog'
import type { ParsedCatalog, UnifiedWorkPage } from '../worker/types'

const page: UnifiedWorkPage = {
  works: [
    {
      id: 'w-more',
      mergeKey: 'старий і море|ернест гемінґвей',
      title: 'Старий і море',
      author: 'Ернест Гемінґвей',
      editions: [
        { id: 'e1', sources: [{ sourceId: 'sound-books', url: 'https://s/old-man' }, { sourceId: 'sluhay', url: 'https://sl/old-man' }] },
      ],
    },
    {
      id: 'w-cycle',
      mergeKey: 'гайдамаки|тарас шевченко',
      title: 'Гайдамаки',
      author: 'Тарас Шевченко',
      editions: [{ id: 'e2', sources: [{ sourceId: 'fourread', url: 'https://4read.org/haidamaky' }] }],
    },
  ],
}

const fourreadHome: ParsedCatalog = {
  sections: [
    { id: 'new-arrivals', title: 'Новинки', cards: [] },
    { id: 'series', title: 'Цикли', cards: [{ url: 'https://4read.org/series/shevchenko', title: 'Шевченківські поеми', author: '', coverImageUrl: undefined }] },
  ],
}

beforeEach(() => {
  globalThis.indexedDB = new IDBFactory()
  setUiLocale('uk')
  vi.spyOn(api, 'workFeed').mockResolvedValue(page)
  vi.spyOn(api, 'workSearch').mockResolvedValue(page)
  vi.spyOn(api, 'catalog').mockResolvedValue(fourreadHome)
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  window.localStorage.clear()
})

describe('Огляд shelves', () => {
  it('renders the shelves in the #302 pin order, feed last', async () => {
    render(<Catalog onOpenBook={vi.fn()} onPlay={vi.fn(async () => true)} />)

    // «Відкрити нове» group → «Новинки» → «Цикли» → «Колекції» → feed.
    await waitFor(() => expect(screen.getByRole('heading', { level: 2, name: 'Відкрити нове' })).toBeTruthy())
    const headings = screen.getAllByRole('heading').map((h) => h.textContent ?? '')
    const order = ['Відкрити нове', 'Новинки', 'Цикли', 'Нобелівські лауреати']
    const positions = order.map((name) => headings.findIndex((h) => h.startsWith(name)))
    expect(positions.every((p) => p >= 0)).toBe(true)
    expect(positions).toEqual([...positions].sort((a, b) => a - b))
    // The infinite feed is the LAST element of the screen (the #302 pin).
    expect(headings.findIndex((h) => h.startsWith('Усі джерела'))).toBeGreaterThan(positions[3]!)
  })

  it('the «Новинки» rail shows one Work with all its source badges (Work-dedup)', async () => {
    render(<Catalog onOpenBook={vi.fn()} onPlay={vi.fn(async () => true)} />)

    // «Старий і море» comes from TWO sources — one merged poster, both
    // badges (it also appears on its collection shelf and in the feed).
    await waitFor(() => expect(screen.getAllByText('Старий і море').length).toBeGreaterThan(0))
    expect(screen.getAllByText('Старий і море').length).toBeGreaterThanOrEqual(3)
    expect(screen.getAllByText('Sound-Books').length).toBeGreaterThan(0)
    expect(screen.getAllByText('Sluhay').length).toBeGreaterThan(0)
  })

  it('renders «Цикли» from the worker and «Колекції» matched locally', async () => {
    render(<Catalog onOpenBook={vi.fn()} onPlay={vi.fn(async () => true)} />)

    await waitFor(() => expect(screen.getByText('Шевченківські поеми')).toBeTruthy())
    // «Старий і море» matches the shipped nobel.json asset (Гемінґвей).
    expect(screen.getByRole('heading', { level: 3, name: 'Нобелівські лауреати' })).toBeTruthy()
    // «Гайдамаки» (Шевченко) does NOT match the Shevchenko asset entry
    // «Кобзар» — no fabricated placement.
    expect(screen.queryByRole('heading', { level: 3, name: 'Шевченківська премія' })).toBeNull()
  })

  it('a fresh snapshot answers without a network call', async () => {
    await writeWarm(warmKey('catalog', 'all'), page)
    const feedSpy = vi.mocked(api.workFeed)
    render(<Catalog onOpenBook={vi.fn()} onPlay={vi.fn(async () => true)} />)

    await waitFor(() => expect(screen.getAllByText('Старий і море').length).toBeGreaterThan(0))
    // The snapshot was fresh → the feed never hit the network.
    expect(feedSpy).not.toHaveBeenCalled()
  })

  it('offline cold start shows the last (stale) snapshot with an honest note', async () => {
    // Seed a snapshot on a CONTROLLED clock, then advance it past the
    // new-arrivals TTL and fail the feed — the write stamps exactly the
    // mocked instant, so the staleness decision is deterministic.
    const clock = vi.spyOn(Date, 'now').mockReturnValue(1_700_000_000_000)
    await writeWarm(warmKey('catalog', 'all'), page)
    clock.mockReturnValue(1_700_000_000_000 + NEW_ARRIVALS_TTL_MS + 1)
    vi.mocked(api.workFeed).mockResolvedValue(null)

    render(<Catalog onOpenBook={vi.fn()} onPlay={vi.fn(async () => true)} />)

    await waitFor(() => expect(screen.getAllByText('Старий і море').length).toBeGreaterThan(0))
    expect(screen.getByText(/Показуємо останній збережений каталог/)).toBeTruthy()
  })

  it('the explicit refresh bypasses the snapshot TTL', async () => {
    await writeWarm(warmKey('catalog', 'all'), page)
    const feedSpy = vi.mocked(api.workFeed)
    const user = userEvent.setup()
    render(<Catalog onOpenBook={vi.fn()} onPlay={vi.fn(async () => true)} />)

    await waitFor(() => expect(screen.getAllByText('Старий і море').length).toBeGreaterThan(0))
    expect(feedSpy).not.toHaveBeenCalled()

    await user.click(screen.getByRole('button', { name: 'Оновити каталог' }))
    await waitFor(() => expect(feedSpy).toHaveBeenCalledTimes(1))
  })

  it('shelf cards open the Work through its real Source', async () => {
    const open = vi.fn()
    const user = userEvent.setup()
    render(<Catalog onOpenBook={open} onPlay={vi.fn(async () => true)} />)

    await waitFor(() => expect(screen.getAllByText('Старий і море').length).toBeGreaterThan(0))
    await user.click(screen.getAllByRole('button', { name: 'Відкрити книгу: Старий і море' })[0]!)
    expect(open).toHaveBeenCalledWith('https://s/old-man', 'sound-books')
  })
})