// @vitest-environment jsdom
/**
 * W3.3 — the feed filters: the sticky toolbar + sheet with the Android
 * WorkFacetFilter contract (OR inside a dimension, AND across, empty =
 * inactive), genre options from the homepage nav through the worker, the
 * genre-union feed with honest counts, duration buckets from real
 * durations, and the sheet's focus contract.
 */
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import 'fake-indexeddb/auto'
import { IDBFactory } from 'fake-indexeddb'
import { api } from '../api/client'
import { setUiLocale } from '../i18n/locale'
import { Catalog } from './Catalog'
import type { ParsedCatalog, UnifiedWorkPage } from '../worker/types'

const page: UnifiedWorkPage = {
  works: [
    {
      id: 'w-more',
      mergeKey: 'старий і море|ернест гемінґвей',
      title: 'Старий і море',
      author: 'Ернест Гемінґвей',
      editions: [{ id: 'e1', durationSeconds: 6 * 3600, sources: [{ sourceId: 'sound-books', url: 'https://s/old-man' }] }],
    },
    {
      id: 'w-short',
      mergeKey: 'гайдамаки|тарас шевченко',
      title: 'Гайдамаки',
      author: 'Тарас Шевченко',
      editions: [{ id: 'e2', durationSeconds: 2 * 3600, sources: [{ sourceId: 'fourread', url: 'https://4read.org/haidamaky' }] }],
    },
  ],
}

const fourreadHome: ParsedCatalog = {
  sections: [
    { id: 'new-arrivals', title: 'Новинки', cards: [] },
    { id: 'series', title: 'Цикли', cards: [] },
    { id: 'genres', title: 'Жанри', cards: [
      { url: 'https://4read.org/kazka/', title: 'Казка', author: '' },
      { url: 'https://4read.org/fentezi/', title: 'Фентезі', author: '' },
    ] },
  ],
}

const kazkaPage: ParsedCatalog = {
  sections: [{
    id: 'category',
    title: 'Книги',
    cards: [
      { url: 'https://4read.org/k1.html', title: 'Казкова книга', author: 'Автор А', durationSeconds: 7 * 3600 },
      { url: 'https://4read.org/k2.html', title: 'Коротка казка', author: 'Автор Б', durationSeconds: 2 * 3600 },
      { url: 'https://4read.org/k3.html', title: 'Без тривалості', author: 'Автор В' },
    ],
  }],
}

const fenteziPage: ParsedCatalog = {
  sections: [{
    id: 'category',
    title: 'Книги',
    cards: [
      { url: 'https://4read.org/f1.html', title: 'Фентезійний роман', author: 'Автор Г', durationSeconds: 12 * 3600 },
      // The same bibliographic Work as on the kazka page — one Work, two genres.
      { url: 'https://4read.org/k1.html', title: 'Казкова книга', author: 'Автор А', durationSeconds: 7 * 3600 },
    ],
  }],
}

beforeEach(() => {
  globalThis.indexedDB = new IDBFactory()
  setUiLocale('uk')
  vi.spyOn(api, 'workFeed').mockResolvedValue(page)
  vi.spyOn(api, 'workSearch').mockResolvedValue(page)
  vi.spyOn(api, 'catalog').mockImplementation(async (_source, url) => {
    if (url === undefined) return fourreadHome
    if (url === 'https://4read.org/kazka/') return kazkaPage
    if (url === 'https://4read.org/fentezi/') return fenteziPage
    return null
  })
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  window.localStorage.clear()
})

async function openSheet(user: ReturnType<typeof userEvent.setup>): Promise<void> {
  await waitFor(() => expect(screen.getByRole('button', { name: 'Фільтри' })).toBeTruthy())
  await user.click(screen.getByRole('button', { name: 'Фільтри' }))
  await waitFor(() => expect(screen.getByRole('dialog', { name: 'Фільтри' })).toBeTruthy())
}

describe('W3.3 feed filters', () => {
  it('the sticky toolbar opens the sheet with genre options from the homepage nav', async () => {
    const user = userEvent.setup()
    render(<Catalog onOpenBook={vi.fn()} onPlay={vi.fn(async () => true)} />)
    await openSheet(user)

    const dialog = screen.getByRole('dialog', { name: 'Фільтри' })
    expect(within(dialog).getByRole('button', { name: 'Усі' })).toBeTruthy()
    expect(within(dialog).getByRole('button', { name: 'Казка' })).toBeTruthy()
    expect(within(dialog).getByRole('button', { name: 'Фентезі' })).toBeTruthy()
    expect(within(dialog).getByRole('button', { name: 'До 5 год' })).toBeTruthy()
    expect(within(dialog).getByRole('button', { name: '5–10 год' })).toBeTruthy()
    expect(within(dialog).getByRole('button', { name: '10–20 год' })).toBeTruthy()
    expect(within(dialog).getByRole('button', { name: 'Понад 20 год' })).toBeTruthy()
    // The heading takes focus on open (the sheet a11y contract).
    expect(dialog.querySelector('h2')).toBe(document.activeElement)
  })

  it('a genre selection swaps the feed to the genre union with honest counts', async () => {
    const user = userEvent.setup()
    render(<Catalog onOpenBook={vi.fn()} onPlay={vi.fn(async () => true)} />)
    await openSheet(user)
    await user.click(screen.getByRole('button', { name: 'Казка' }))

    // The genre page was fetched through the worker and its works show.
    // NB: the accessible name flattens the count span to «Казка· 3» (no
    // space before the dot) — the regex matches the real accessible name.
    await waitFor(() => expect(screen.getByRole('heading', { level: 2, name: /Казка· 3$/ })).toBeTruthy())
    const section = screen.getByRole('heading', { level: 2, name: /Казка· 3$/ }).closest('section')!
    expect(within(section).getByRole('button', { name: 'Відкрити книгу: Казкова книга' })).toBeTruthy()
    expect(within(section).getByRole('button', { name: 'Відкрити книгу: Коротка казка' })).toBeTruthy()
    expect(within(section).getByRole('button', { name: 'Відкрити книгу: Без тривалості' })).toBeTruthy()
    // The regular feed's works are NOT part of the genre union (the rail and
    // collections above may still show them — the shelves stay unfiltered).
    expect(within(section).queryByRole('button', { name: 'Відкрити книгу: Старий і море' })).toBeNull()
    // The toolbar lights up with the selection.
    expect(screen.getByRole('button', { name: 'Фільтри' }).getAttribute('aria-pressed')).toBe('true')
  })

  it('two genres compose with OR and dedupe by MergeKey', async () => {
    const user = userEvent.setup()
    render(<Catalog onOpenBook={vi.fn()} onPlay={vi.fn(async () => true)} />)
    await openSheet(user)
    await user.click(screen.getByRole('button', { name: 'Казка' }))
    await user.click(screen.getByRole('button', { name: 'Фентезі' }))

    // Union: 3 kazka works + 1 fentezi-only work; «Казкова книга» appears once.
    await waitFor(() => expect(screen.getByRole('heading', { level: 2, name: /Казка, Фентезі· 4$/ })).toBeTruthy())
    expect(screen.getByRole('button', { name: 'Відкрити книгу: Фентезійний роман' })).toBeTruthy()
    expect(screen.getAllByRole('button', { name: 'Відкрити книгу: Казкова книга' })).toHaveLength(1)
  })

  it('a duration bucket filters the regular feed by REAL durations (AND across dimensions)', async () => {
    const user = userEvent.setup()
    render(<Catalog onOpenBook={vi.fn()} onPlay={vi.fn(async () => true)} />)
    await openSheet(user)
    await user.click(screen.getByRole('button', { name: 'До 5 год' }))

    await waitFor(() => expect(screen.getByRole('heading', { level: 2, name: /Усі джерела· 1$/ })).toBeTruthy())
    const section = screen.getByRole('heading', { level: 2, name: /Усі джерела· 1$/ }).closest('section')!
    expect(within(section).getByRole('button', { name: 'Відкрити книгу: Гайдамаки' })).toBeTruthy()
    expect(within(section).queryByRole('button', { name: 'Відкрити книгу: Старий і море' })).toBeNull()
  })

  it('genre AND duration compose across dimensions', async () => {
    const user = userEvent.setup()
    render(<Catalog onOpenBook={vi.fn()} onPlay={vi.fn(async () => true)} />)
    await openSheet(user)
    await user.click(screen.getByRole('button', { name: 'Казка' }))
    await user.click(screen.getByRole('button', { name: '5–10 год' }))

    // Of the kazka union (7h, 2h, unknown), only the 7h Work is 5–10 год;
    // the unknown-duration Work never matches (Android EXISTS semantics).
    await waitFor(() => expect(screen.getByRole('heading', { level: 2, name: /Казка· 1$/ })).toBeTruthy())
    expect(screen.getByRole('button', { name: 'Відкрити книгу: Казкова книга' })).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Відкрити книгу: Без тривалості' })).toBeNull()
  })

  it('«Усі» clears the genre dimension; reset clears everything; done closes', async () => {
    const user = userEvent.setup()
    render(<Catalog onOpenBook={vi.fn()} onPlay={vi.fn(async () => true)} />)
    await openSheet(user)
    await user.click(screen.getByRole('button', { name: 'Казка' }))
    await user.click(screen.getByRole('button', { name: 'До 5 год' }))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Фільтри' }).getAttribute('aria-pressed')).toBe('true'))

    // «Усі» clears only the genre dimension (scoped: the language pills row
    // has its own «Усі» chip).
    await user.click(within(screen.getByRole('dialog', { name: 'Фільтри' })).getByRole('button', { name: 'Усі' }))
    await waitFor(() => expect(screen.getByRole('heading', { level: 2, name: /Усі джерела· 1$/ })).toBeTruthy())

    // Reset clears everything — the full feed returns.
    await user.click(within(screen.getByRole('dialog', { name: 'Фільтри' })).getByRole('button', { name: 'Скинути все' }))
    await waitFor(() => expect(screen.getByRole('heading', { level: 2, name: /Усі джерела· 2$/ })).toBeTruthy())
    expect(screen.getByRole('button', { name: 'Фільтри' }).getAttribute('aria-pressed')).toBe('false')

    await user.click(screen.getByRole('button', { name: 'Готово' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  })

  it('closing the sheet returns focus to the toolbar trigger', async () => {
    const user = userEvent.setup()
    render(<Catalog onOpenBook={vi.fn()} onPlay={vi.fn(async () => true)} />)
    const trigger = await screen.findByRole('button', { name: 'Фільтри' })
    await user.click(trigger)
    await waitFor(() => expect(screen.getByRole('dialog', { name: 'Фільтри' })).toBeTruthy())
    await user.click(screen.getByRole('button', { name: 'Готово' }))
    await waitFor(() => expect(document.activeElement?.textContent).toContain('Фільтри'))
  })

  it('a missing genre nav leaves the Жанри section honestly absent', async () => {
    const user = userEvent.setup()
    vi.mocked(api.catalog).mockImplementation(async (_source, url) => {
      if (url === undefined) return { sections: [{ id: 'new-arrivals', title: 'Новинки', cards: [] }] } satisfies ParsedCatalog
      if (url === 'https://4read.org/kazka/') return kazkaPage
      return null
    })
    render(<Catalog onOpenBook={vi.fn()} onPlay={vi.fn(async () => true)} />)
    await openSheet(user)

    const dialog = screen.getByRole('dialog', { name: 'Фільтри' })
    expect(within(dialog).queryByRole('button', { name: 'Казка' })).toBeNull()
    expect(within(dialog).getByRole('button', { name: 'До 5 год' })).toBeTruthy()
  })
})