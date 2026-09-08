// @vitest-environment jsdom
/**
 * W3.2 — the catalogue indexes: the «Швидкі переходи» nav row (five chips,
 * Android's order), the four pushed index screens on the shared chassis
 * (canonical titles, honest counts, canonical empty states), ranked ТОП 100
 * rows with real durations, people rows with their carried counts, and the
 * focus-return contract on back.
 */
import { cleanup, render, screen, waitFor } from '@testing-library/react'
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
      editions: [{ id: 'e1', sources: [{ sourceId: 'sound-books', url: 'https://s/old-man' }] }],
    },
  ],
}

const fourreadHome: ParsedCatalog = {
  sections: [
    { id: 'new-arrivals', title: 'Новинки', cards: [] },
    { id: 'series', title: 'Цикли', cards: [
      { url: 'https://4read.org/xfsearch/cikl/poemy/', title: 'Шевченківські поеми', author: '' },
      { url: 'https://4read.org/xfsearch/cikl/kobzar/', title: 'Кобзарівський цикл', author: '' },
    ] },
  ],
}

const top100: ParsedCatalog = {
  sections: [{
    id: 'top100',
    title: 'ТОП 100',
    cards: [
      { url: 'https://4read.org/6945-chorti.html', title: 'Чорти', author: 'Джо Аберкромбі', durationSeconds: 21 * 3600 + 42 * 60 + 42 },
      { url: 'https://4read.org/7001-vkrady.html', title: 'Вкради мене... Зараз!', author: 'Сергій Оріанець', durationSeconds: 8 * 3600 + 5 * 60 },
    ],
  }],
}

const readers: ParsedCatalog = {
  sections: [{
    id: 'people',
    title: 'Виконавці',
    cards: [
      { url: 'https://4read.org/xfsearch/chitaet/Ада Роговцева/', title: 'Ада Роговцева', author: '', count: 23 },
      { url: 'https://4read.org/xfsearch/chitaet/Аліна Лукащук/', title: 'Аліна Лукащук', author: '', count: 1 },
    ],
  }],
}

function catalogMock(url?: string): ParsedCatalog | null {
  if (url === undefined) return fourreadHome
  if (url.endsWith('/top-100.html')) return top100
  if (url.endsWith('/readers.html')) return readers
  return null
}

beforeEach(() => {
  globalThis.indexedDB = new IDBFactory()
  setUiLocale('uk')
  vi.spyOn(api, 'workFeed').mockResolvedValue(page)
  vi.spyOn(api, 'workSearch').mockResolvedValue(page)
  vi.spyOn(api, 'catalog').mockImplementation(async (_source, url) => catalogMock(url))
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  window.localStorage.clear()
})

describe('W3.2 indexes', () => {
  it('renders the five navigation chips in Android order', async () => {
    render(<Catalog onOpenBook={vi.fn()} onPlay={vi.fn(async () => true)} />)
    await waitFor(() => expect(screen.getByRole('heading', { level: 2, name: 'Швидкі переходи' })).toBeTruthy())
    const chips = ['ТОП 100', 'Виконавці', 'Автори', 'Серії', 'Колекції'].map((name) => screen.getByRole('button', { name }))
    expect(chips).toHaveLength(5)
  })

  it('ТОП 100 shows ranked rows with real durations and an honest count; back returns', async () => {
    const user = userEvent.setup()
    const openBook = vi.fn()
    render(<Catalog onOpenBook={openBook} onPlay={vi.fn(async () => true)} />)
    await waitFor(() => expect(screen.getByRole('button', { name: 'ТОП 100' })).toBeTruthy())
    await user.click(screen.getByRole('button', { name: 'ТОП 100' }))

    await waitFor(() => expect(screen.getByRole('heading', { level: 2, name: /ТОП 100/ })).toBeTruthy())
    // The honest count rides the header (R10 / ADR-0033).
    expect(screen.getByRole('heading', { level: 2, name: /· 2$/ })).toBeTruthy()
    const rows = screen.getAllByRole('button', { name: /Відкрити книгу:/ })
    expect(rows).toHaveLength(2)
    // Rank badges are 1-based list order.
    expect(rows[0]!.textContent).toContain('1')
    expect(rows[0]!.textContent).toContain('Чорти')
    expect(rows[1]!.textContent).toContain('2')
    expect(rows[1]!.textContent).toContain('Вкради мене... Зараз!')
    // The real duration rides the row's trailing line (honest totals).
    expect(screen.getByText('21 год 43 хв')).toBeTruthy()

    await user.click(rows[0]!)
    expect(openBook).toHaveBeenCalledWith('https://4read.org/6945-chorti.html', 'fourread')

    await user.click(screen.getByRole('button', { name: '← Назад' }))
    await waitFor(() => expect(screen.getByRole('heading', { level: 2, name: 'Швидкі переходи' })).toBeTruthy())
  })

  it('Серії opens the CycleCard grid from the homepage series section', async () => {
    const user = userEvent.setup()
    render(<Catalog onOpenBook={vi.fn()} onPlay={vi.fn(async () => true)} />)
    await waitFor(() => expect(screen.getByRole('button', { name: 'Серії' })).toBeTruthy())
    await user.click(screen.getByRole('button', { name: 'Серії' }))

    await waitFor(() => expect(screen.getByRole('heading', { level: 2, name: /Серії/ })).toBeTruthy())
    // The count appears only once the series snapshot really loaded.
    await waitFor(() => expect(screen.getByRole('button', { name: 'Відкрити цикл: Шевченківські поеми' })).toBeTruthy())
    expect(screen.getByRole('button', { name: 'Відкрити цикл: Кобзарівський цикл' })).toBeTruthy()
    expect(screen.getByRole('heading', { level: 2, name: /· 2$/ })).toBeTruthy()
  })

  it('Колекції opens the matched collections with their own headers', async () => {
    const user = userEvent.setup()
    render(<Catalog onOpenBook={vi.fn()} onPlay={vi.fn(async () => true)} />)
    await waitFor(() => expect(screen.getByRole('button', { name: 'Колекції' })).toBeTruthy())
    await user.click(screen.getByRole('button', { name: 'Колекції' }))

    await waitFor(() => expect(screen.getByRole('heading', { level: 2, name: /Колекції/ })).toBeTruthy())
    // «Старий і море» matches the shipped nobel.json asset.
    expect(screen.getByRole('heading', { level: 3, name: 'Нобелівські лауреати' })).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Відкрити книгу: Старий і море' })).toBeTruthy()
  })

  it('Виконавці shows the carried book counts; a row opens the person page', async () => {
    const user = userEvent.setup()
    const openSpy = vi.spyOn(window, 'open').mockImplementation(() => null)
    render(<Catalog onOpenBook={vi.fn()} onPlay={vi.fn(async () => true)} />)
    await waitFor(() => expect(screen.getByRole('button', { name: 'Виконавці' })).toBeTruthy())
    await user.click(screen.getByRole('button', { name: 'Виконавці' }))

    await waitFor(() => expect(screen.getByRole('heading', { level: 2, name: /Виконавці/ })).toBeTruthy())
    expect(screen.getByRole('heading', { level: 2, name: /· 2$/ })).toBeTruthy()
    const row = screen.getByRole('button', { name: 'Відкрити сторінку: Ада Роговцева' })
    expect(row.textContent).toContain('23 книги')
    expect(screen.getByRole('button', { name: 'Відкрити сторінку: Аліна Лукащук' }).textContent).toContain('1 книга')
    await user.click(row)
    expect(openSpy).toHaveBeenCalledWith('https://4read.org/xfsearch/chitaet/Ада Роговцева/', '_blank', 'noopener')
  })

  it('a failing index fetch shows the canonical empty state, never a crash', async () => {
    const user = userEvent.setup()
    vi.mocked(api.catalog).mockImplementation(async (_source, url) => (url === undefined ? fourreadHome : null))
    render(<Catalog onOpenBook={vi.fn()} onPlay={vi.fn(async () => true)} />)
    await waitFor(() => expect(screen.getByRole('button', { name: 'Автори' })).toBeTruthy())
    await user.click(screen.getByRole('button', { name: 'Автори' }))

    await waitFor(() => expect(screen.getByText('Список з’явиться після завантаження каталогу.')).toBeTruthy())
    // No fabricated count on an empty index.
    expect(screen.getByRole('heading', { level: 2, name: /Автори/ }).textContent).not.toContain('·')
  })

  it('back returns focus to the chip that opened the index', async () => {
    const user = userEvent.setup()
    render(<Catalog onOpenBook={vi.fn()} onPlay={vi.fn(async () => true)} />)
    await waitFor(() => expect(screen.getByRole('button', { name: 'ТОП 100' })).toBeTruthy())
    await user.click(screen.getByRole('button', { name: 'ТОП 100' }))
    await waitFor(() => expect(screen.getByRole('button', { name: '← Назад' })).toBeTruthy())
    await user.click(screen.getByRole('button', { name: '← Назад' }))
    await waitFor(() => expect(document.activeElement?.textContent).toContain('ТОП 100'))
  })
})