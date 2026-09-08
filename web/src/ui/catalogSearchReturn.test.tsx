// @vitest-environment jsdom
/**
 * W3.4 — the global search round-trip (spec-22 T3 pattern): 🔍 expands,
 * ✕/Escape clears+collapses, the results header carries an honest count,
 * and — the AC's core — returning from a book preserves the query AND the
 * scroll place (Android keeps the screen on the backstack; the web's
 * Catalog remounts, so the tab remembers both).
 */
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import 'fake-indexeddb/auto'
import { IDBFactory } from 'fake-indexeddb'
import { api } from '../api/client'
import { setUiLocale } from '../i18n/locale'
import { Catalog } from './Catalog'
import { resetSearchMemory, searchMemory } from './searchMemory'
import type { UnifiedWorkPage } from '../worker/types'

const page: UnifiedWorkPage = {
  works: [
    {
      id: 'w-more',
      mergeKey: 'старий і море|ернест гемінґвей',
      title: 'Старий і море',
      author: 'Ернест Гемінґвей',
      editions: [{ id: 'e1', sources: [{ sourceId: 'sound-books', url: 'https://s/old-man' }] }],
    },
    {
      id: 'w-short',
      mergeKey: 'гайдамаки|тарас шевченко',
      title: 'Гайдамаки',
      author: 'Тарас Шевченко',
      editions: [{ id: 'e2', sources: [{ sourceId: 'fourread', url: 'https://4read.org/haidamaky' }] }],
    },
  ],
}

beforeEach(() => {
  globalThis.indexedDB = new IDBFactory()
  setUiLocale('uk')
  resetSearchMemory()
  vi.spyOn(api, 'workFeed').mockResolvedValue(page)
  vi.spyOn(api, 'workSearch').mockResolvedValue(page)
  vi.spyOn(api, 'catalog').mockResolvedValue({ sections: [] })
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  window.localStorage.clear()
})

async function search(user: ReturnType<typeof userEvent.setup>, query: string): Promise<void> {
  await user.click(screen.getByRole('button', { name: 'Пошук' }))
  await user.type(screen.getByRole('searchbox'), query)
  await waitFor(() => expect(screen.getByRole('heading', { level: 2, name: /Усі джерела· 2$/ })).toBeTruthy())
}

describe('W3.4 global search', () => {
  it('✕ clears the query and collapses the field', async () => {
    const user = userEvent.setup()
    render(<Catalog onOpenBook={vi.fn()} onPlay={vi.fn(async () => true)} />)
    await search(user, 'мор')
    expect(screen.getByRole('button', { name: 'Відкрити книгу: Старий і море' })).toBeTruthy()

    await user.click(screen.getByRole('button', { name: 'Закрити пошук' }))
    expect(screen.queryByRole('searchbox')).toBeNull()
    // The regular feed is back.
    await waitFor(() => expect(screen.getByRole('heading', { level: 2, name: /Усі джерела· 2$/ })).toBeTruthy())
    // The cleared state is remembered too — a later round-trip lands clean.
    expect(searchMemory.query).toBe('')
  })

  it('Escape clears the query and collapses the field', async () => {
    const user = userEvent.setup()
    render(<Catalog onOpenBook={vi.fn()} onPlay={vi.fn(async () => true)} />)
    await search(user, 'мор')
    await user.keyboard('{Escape}')
    expect(screen.queryByRole('searchbox')).toBeNull()
  })

  it('a book round-trip preserves the query and refetches the results', async () => {
    const user = userEvent.setup()
    const { unmount } = render(<Catalog onOpenBook={vi.fn()} onPlay={vi.fn(async () => true)} />)
    await search(user, 'мор')
    expect(screen.getByRole('button', { name: 'Відкрити книгу: Старий і море' })).toBeTruthy()

    // The book opens: the whole tab unmounts (App replaces it with BookPage).
    unmount()
    render(<Catalog onOpenBook={vi.fn()} onPlay={vi.fn(async () => true)} />)

    // Back on the tab: the field is expanded with the query and the results
    // came back (the search refetched through the worker).
    const field = await screen.findByRole('searchbox')
    expect((field as HTMLInputElement).value).toBe('мор')
    await waitFor(() => expect(screen.getByRole('button', { name: 'Відкрити книгу: Старий і море' })).toBeTruthy())
  })

  it('the scroll place is restored once the content settles', async () => {
    const user = userEvent.setup()
    const scrollTo = vi.spyOn(window, 'scrollTo').mockImplementation(() => undefined)
    Object.defineProperty(window, 'scrollY', { value: 321, configurable: true, writable: true })

    const { unmount } = render(<Catalog onOpenBook={vi.fn()} onPlay={vi.fn(async () => true)} />)
    await search(user, 'мор')
    // The unmount records the place (a book opening at 321px).
    unmount()
    render(<Catalog onOpenBook={vi.fn()} onPlay={vi.fn(async () => true)} />)

    await waitFor(() => expect(screen.getByRole('button', { name: 'Відкрити книгу: Старий і море' })).toBeTruthy())
    expect(scrollTo).toHaveBeenCalledWith(0, 321)
    // Exactly once: the memory is consumed.
    expect(scrollTo).toHaveBeenCalledTimes(1)
  })

  it('the results header carries an honest count', async () => {
    const user = userEvent.setup()
    render(<Catalog onOpenBook={vi.fn()} onPlay={vi.fn(async () => true)} />)
    await search(user, 'мор')
    // NB: the accessible name flattens the count span («Усі джерела· 2»).
    expect(screen.getByRole('heading', { level: 2, name: /Усі джерела· 2$/ })).toBeTruthy()
  })
})